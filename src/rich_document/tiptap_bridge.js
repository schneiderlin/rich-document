import { Editor, Extension, Node } from "@tiptap/core";
import { UniqueID } from "@tiptap/extension-unique-id";
import { NodeSelection, Plugin, PluginKey, Selection, TextSelection } from "@tiptap/pm/state";
import { Step } from "@tiptap/pm/transform";
import { closeHistory, redoDepth, undoDepth } from "@tiptap/pm/history";
import { StarterKit } from "@tiptap/starter-kit";

const lifecycle = {
  adaptersCreated: 0,
  adaptersDestroyed: 0,
  nodeViewsCreated: 0,
  nodeViewsDestroyed: 0,
};

export const clone = (value) => JSON.parse(JSON.stringify(value));
export const newId = (prefix) => `${prefix}-${crypto.randomUUID()}`;

export function nodeViewCreated() { lifecycle.nodeViewsCreated += 1; }
export function nodeViewDestroyed() { lifecycle.nodeViewsDestroyed += 1; }

export function findNode(doc, nodeId) {
  let found = null;
  doc.descendants((node, pos) => {
    if (!found && node.attrs?.nodeId === nodeId) {
      found = { node, pos };
      return false;
    }
    return !found;
  });
  return found;
}

export function createDocumentNode(attributes = {}) {
  return Node.create({
    name: "doc",
    topNode: true,
    content: "block+",
    addAttributes() { return attributes; },
  });
}

function selectionProjection(editor, canonicalTypes) {
  const { selection, doc } = editor.state;
  const direct = selection instanceof NodeSelection ? selection.node.attrs?.nodeId : null;
  const canonicalType = (type) => canonicalTypes[type] ?? type;
  if (direct) return { nodeIds: [direct], type: canonicalType(selection.node.type.name) };
  const resolved = doc.resolve(selection.from);
  for (let depth = resolved.depth; depth > 0; depth -= 1) {
    const id = resolved.node(depth).attrs?.nodeId;
    if (id) return { nodeIds: [id], type: canonicalType(resolved.node(depth).type.name) };
  }
  return { nodeIds: [] };
}

function adjacentTopLevelBlock(doc, selectedPos, direction) {
  const blocks = [];
  doc.forEach((node, pos) => blocks.push({ node, pos }));
  const selectedIndex = blocks.findIndex(({ pos }) => pos === selectedPos);
  if (selectedIndex < 0) return null;
  return blocks[selectedIndex + direction] ?? null;
}

function selectionAtBlock(doc, block, direction) {
  if (block.node.isTextblock) {
    const offset = direction > 0 ? 1 : block.node.nodeSize - 1;
    return TextSelection.create(doc, block.pos + offset);
  }
  if (!block.node.isAtom) {
    const innerPos = direction > 0 ? block.pos + 1 : block.pos + block.node.nodeSize - 1;
    const textSelection = TextSelection.findFrom(doc.resolve(innerPos), direction, true);
    if (textSelection) return textSelection;
  }
  return NodeSelection.isSelectable(block.node) ? NodeSelection.create(doc, block.pos) : null;
}

export function createRichDocumentEditor(element, content, options = {}, config = {}) {
  lifecycle.adaptersCreated += 1;
  let composing = false;
  let pendingProjection = false;
  let destroyed = false;
  let heldTransaction = null;
  let replaying = false;
  let externalContext = null;
  const runtime = config.createContext?.(options) ?? {};
  runtime.renderers ??= new Set();
  const atomicBlockNames = new Set(config.atomicBlockNames ?? []);
  const emitProjection = (editor) => {
    if (composing) {
      pendingProjection = true;
      return;
    }
    pendingProjection = false;
    options.onDocumentUpdate?.(clone(editor.getJSON()));
  };
  const editor = new Editor({
    element,
    extensions: [
      StarterKit.configure({ document: false }),
      config.documentNode ?? createDocumentNode(),
      ...(config.extensionsBeforeUniqueId?.(runtime) ?? []),
      UniqueID.configure({
        attributeName: "nodeId",
        types: config.idNodeTypes ?? [
          "paragraph", "heading", "bulletList", "orderedList", "listItem", "codeBlock", "horizontalRule",
        ],
        generateID: ({ node }) => newId(node.type.name),
      }),
      ...(config.extensionsAfterUniqueId?.(runtime) ?? []),
      Extension.create({
        name: "richDocumentChangeGuard",
        addProseMirrorPlugins() {
          return [new Plugin({
            key: new PluginKey("richDocumentChangeGuard"),
            filterTransaction(transaction) {
              if (!transaction.docChanged) return true;
              if (transaction.doc.eq(transaction.before)) return false;
              if (replaying || !config.shouldInterceptChanges?.(runtime)) return true;
              if (!heldTransaction) {
                const token = crypto.randomUUID();
                heldTransaction = {
                  token,
                  steps: transaction.steps.map((step) => step.toJSON()),
                  selection: transaction.selection.toJSON(),
                };
                queueMicrotask(() => options.onEditIntercept?.(token));
              }
              return false;
            },
          })];
        },
      }),
    ],
    content,
    editorProps: {
      attributes: config.editorAttributes ?? {
        class: "rich-document-editor__content",
        "aria-label": "Rich document editor",
      },
      handleKeyDown(view, event) {
        const { selection } = view.state;
        if (event.key === "Escape") {
          event.preventDefault();
          view.dom.blur();
          options.onExitEditing?.();
          return true;
        }
        if ((event.key === "ArrowDown" || event.key === "ArrowUp")
            && selection instanceof NodeSelection
            && atomicBlockNames.has(selection.node.type.name)) {
          const direction = event.key === "ArrowDown" ? 1 : -1;
          const adjacent = adjacentTopLevelBlock(view.state.doc, selection.from, direction);
          const nextSelection = adjacent && selectionAtBlock(view.state.doc, adjacent, direction);
          if (nextSelection) {
            event.preventDefault();
            view.dispatch(view.state.tr.setSelection(nextSelection).scrollIntoView());
            view.focus();
            return true;
          }
        }
        return config.handleKeyDown?.({ view, event, options, runtime }) ?? false;
      },
      handleDOMEvents: {
        compositionstart() {
          composing = true;
          options.onCompositionChange?.(true);
          return false;
        },
        compositionend() {
          composing = false;
          options.onCompositionChange?.(false);
          if (pendingProjection) queueMicrotask(() => emitProjection(editor));
          return false;
        },
      },
    },
    onUpdate: ({ editor: updated }) => emitProjection(updated),
    onSelectionUpdate: ({ editor: updated }) => {
      options.onSelectionUpdate?.(selectionProjection(updated, config.canonicalTypes ?? {}));
    },
  });

  const dispatchNodeAttrs = (nodeId, updater) => {
    const found = findNode(editor.state.doc, nodeId);
    if (!found) return false;
    const tr = closeHistory(editor.state.tr.setNodeMarkup(
      found.pos,
      undefined,
      updater(clone(found.node.attrs)),
    ));
    editor.view.dispatch(tr);
    return true;
  };
  const insertionPos = () => {
    if (editor.state.selection instanceof NodeSelection) return editor.state.selection.to;
    return editor.state.selection.$to.after(1);
  };
  const focusNodeById = (nodeId) => {
    const found = findNode(editor.state.doc, nodeId);
    if (!found) return false;
    const textSelection = found.node.isTextblock
      ? TextSelection.near(editor.state.doc.resolve(found.pos + 1))
      : (!found.node.isAtom
          ? TextSelection.findFrom(editor.state.doc.resolve(found.pos + 1), 1, true)
          : null);
    const selection = textSelection ?? NodeSelection.create(editor.state.doc, found.pos);
    editor.view.dispatch(editor.state.tr.setSelection(selection).scrollIntoView());
    editor.view.focus();
    return true;
  };
  const insertJSON = (json) => {
    const inserted = editor.commands.insertContentAt(insertionPos(), json);
    if (inserted && json.attrs?.nodeId) focusNodeById(json.attrs.nodeId);
    return inserted;
  };
  const makeBlock = (type) => {
    const ids = {
      paragraph: "paragraph",
      heading: "heading",
      bulletList: "list",
      orderedList: "list",
      codeBlock: "code",
      horizontalRule: "divider",
    };
    if (type === "bulletList" || type === "orderedList") {
      return {
        type,
        attrs: { nodeId: newId(ids[type]) },
        content: [{
          type: "listItem",
          attrs: { nodeId: newId("item") },
          content: [{ type: "paragraph", attrs: { nodeId: newId("paragraph") } }],
        }],
      };
    }
    const block = { type, attrs: { nodeId: newId(ids[type] ?? type) } };
    if (type === "heading") block.attrs.level = 2;
    return block;
  };

  const helpers = {
    editor,
    runtime,
    insertJSON,
    dispatchNodeAttrs,
    focusNodeById,
  };
  const api = {
    getJSON: () => clone(editor.getJSON()),
    getHistory: () => ({ undo: undoDepth(editor.state), redo: redoDepth(editor.state) }),
    undo: () => editor.commands.undo(),
    redo: () => editor.commands.redo(),
    exitBlockEditing() {
      document.activeElement?.blur();
      editor.view.dom.blur();
      options.onExitEditing?.();
    },
    insertBlock: (_position, type) => insertJSON(makeBlock(type)),
    updateNode: (nodeId, patch) => dispatchNodeAttrs(nodeId, (attrs) => ({ ...attrs, ...patch })),
    updateDocument(patch) {
      let tr = editor.state.tr;
      Object.entries(patch).forEach(([key, value]) => {
        tr = tr.setDocAttribute(key, clone(value));
      });
      editor.view.dispatch(closeHistory(tr));
    },
    moveBlock(nodeId, direction) {
      const found = findNode(editor.state.doc, nodeId);
      if (!found || found.pos < 0) return false;
      const positions = [];
      let offset = 0;
      editor.state.doc.forEach((node, _childOffset, index) => {
        positions.push({ node, pos: offset, index });
        offset += node.nodeSize;
      });
      const current = positions.find((entry) => entry.pos === found.pos);
      if (!current) return false;
      const targetIndex = direction === "up" ? current.index - 1 : current.index + 1;
      if (targetIndex < 0 || targetIndex >= editor.state.doc.childCount) return false;
      const target = positions[targetIndex];
      const targetPos = direction === "up" ? target.pos : target.pos + target.node.nodeSize;
      const json = found.node.toJSON();
      let tr = editor.state.tr.delete(found.pos, found.pos + found.node.nodeSize);
      const mappedTarget = tr.mapping.map(targetPos);
      tr = tr.insert(mappedTarget, editor.schema.nodeFromJSON(json));
      editor.view.dispatch(closeHistory(tr));
      return true;
    },
    deleteBlock(nodeId) {
      const found = findNode(editor.state.doc, nodeId);
      if (!found) return false;
      editor.view.dispatch(closeHistory(
        editor.state.tr.delete(found.pos, found.pos + found.node.nodeSize),
      ));
      return true;
    },
    focusNode: focusNodeById,
    setExternalContext(context) {
      const previous = JSON.stringify(externalContext);
      externalContext = context;
      config.updateContext?.(runtime, context);
      if (previous !== JSON.stringify(context)) runtime.renderers.forEach((render) => render());
    },
    resolveHeldTransaction(token, action) {
      if (!heldTransaction || heldTransaction.token !== token) return false;
      const held = heldTransaction;
      heldTransaction = null;
      if (action !== "replay") return true;
      replaying = true;
      try {
        let transaction = editor.state.tr;
        held.steps.forEach((json) => {
          transaction = transaction.step(Step.fromJSON(editor.schema, json));
        });
        transaction = transaction.setSelection(Selection.fromJSON(transaction.doc, held.selection));
        editor.view.dispatch(transaction);
        return true;
      } finally {
        replaying = false;
      }
    },
    scrollNodeIntoView(nodeId) {
      const target = element.querySelector(`[data-node-id="${CSS.escape(nodeId)}"]`);
      if (!target) return false;
      target.scrollIntoView({ block: "center", behavior: "smooth" });
      return true;
    },
    flushProjection: () => emitProjection(editor),
    isComposing: () => composing,
    isDestroyed: () => destroyed,
    destroy() {
      if (destroyed) return;
      destroyed = true;
      editor.destroy();
      lifecycle.adaptersDestroyed += 1;
    },
  };
  Object.assign(api, config.commands?.(helpers) ?? {});
  options.onReady?.(api);
  return api;
}

export function getLifecycleDiagnostics() {
  return {
    ...lifecycle,
    activeNodeViews: lifecycle.nodeViewsCreated - lifecycle.nodeViewsDestroyed,
  };
}
