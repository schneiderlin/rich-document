# rich-document

`rich-document` is an extensible canonical rich-document model with a Tiptap
adapter. It owns rich-text structure, stable node IDs, tree operations, codecs,
and editor lifecycle. A consuming application owns its business node schemas,
NodeViews, commands, validation, and runtime state.

The library is written in Clojure/ClojureScript plus a small JavaScript Tiptap
bridge. It does not depend on re-frame, Reagent, or Dev Panel.

## Public API

### Canonical schema and tree operations

`rich-document.core/document-schema` composes the built-in rich-content grammar
with host-owned root fields, inline nodes, and block nodes:

```clojure
(require '[rich-document.core :as document])

(def schema
  (document/document-schema
   {:root-fields [[:metadata [:map-of string? document/json-value]]]
    :inline-nodes
    {"mention" [:map {:closed true}
                [:id document/non-empty-string]
                [:type [:= "mention"]]
                [:subject document/non-empty-string]]}
    :block-nodes
    {"callout" [:map {:closed true}
                [:id document/non-empty-string]
                [:type [:= "callout"]]
                [:tone [:enum "info" "warning"]]]}}))
```

The same namespace exports `all-nodes`, `node-ids`, `unique-node-ids?`,
`find-node`, `update-node`, `remove-node`, and `move-node`. Tree edits identify
nodes by stable `:id`; they do not use document positions.

### Canonical ↔ Tiptap codecs

`rich-document.tiptap/canonical->tiptap` and `tiptap->canonical` accept one codec
map:

```clojure
{:root->attrs  (fn [document] ...)
 :attrs->root  (fn [attrs] ...)
 :inline-codecs
 {"mention" {:tiptap-type "mention"
              :to-attrs (fn [node] ...)
              :from-attrs (fn [attrs] ...)}}
 :block-codecs
 {"callout" {:tiptap-type "callout"
              :to-attrs (fn [node] ...)
              :from-attrs (fn [attrs] ...)}}}
```

Built-in headings, paragraphs, lists, code blocks, dividers, text, and marks do
not need host codecs.

`tiptap->canonical` takes keyword-keyed Clojure data and throws when it is not a
Tiptap document, so a mismatched input never decodes as an empty document.

The controller reports the document back through `options.onDocumentUpdate` as a
JavaScript value, so decode that payload with
`rich-document.tiptap/js-tiptap->canonical` (or `js->tiptap-data` for the
intermediate data). Do not hand it to `js->clj`: that keeps string keys, which
keyword destructuring reads as `nil`, and it skips objects that do not have
`js/Object` as their prototype — including ProseMirror's attribute maps.

### Tiptap controller

`createRichDocumentEditor(element, content, options, config)` from
`src/rich_document/tiptap_bridge.js` owns the imperative Tiptap lifecycle. Its
configuration surface includes:

- `documentNode`, `idNodeTypes`, and `atomicBlockNames`
- `extensionsBeforeUniqueId(runtime)` and `extensionsAfterUniqueId(runtime)`
- `createContext(options)` and `updateContext(runtime, context)`
- `canonicalTypes`, `editorAttributes`, and `handleKeyDown(...)`
- optional transaction interception and host commands

The returned controller supplies history, insertion, update, move, delete,
focus, composition, transaction replay, external context, and destruction APIs.
`setExternalContext` redraws registered projections without changing the
document or its undo history.

## Consumer requirements

Clojure consumers put `src` on the classpath through a Git or local dependency.
Browser consumers must provide compatible Tiptap 3 packages listed as peer
dependencies in `package.json`.

Use an immutable commit SHA in shared projects:

```clojure
io.github.schneiderlin/rich-document
{:git/url "https://github.com/schneiderlin/rich-document.git"
 :git/sha "<commit-sha>"}
```

For local development, replace the Git coordinate with:

```clojure
io.github.schneiderlin/rich-document
{:local/root "../rich-document"}
```

## Development

```bash
bun install
bun run check
```

## License

MIT
