(ns com.zihao.rich-document.interface
  "Polylith interface facade for the rich-document library.

  Implementation namespaces keep their original names so existing consumers are
  unaffected; Polylith bricks address this brick only through this namespace."
  (:require
   [rich-document.core :as document]
   #?(:cljs [rich-document.tiptap :as tiptap])))

(def non-empty-string
  "Malli schema for a string with at least one character."
  document/non-empty-string)

(defn json-value?
  "Tests whether a value can cross a JSON persistence boundary unchanged.

  Parameters:
  - `value` — value to test.

  Returns: true for `nil`, strings, booleans, numbers, and vectors/maps of those, where
  every map key is a string; false otherwise."
  [value]
  (document/json-value? value))

(def json-value
  "Malli schema accepting exactly the values `json-value?` returns true for."
  document/json-value)

(def mark-schema
  "Malli schema for an inline mark: a closed map dispatched on `:type` over `\"bold\"`,
  `\"italic\"`, `\"strike\"`, `\"inline-code\"`, and `\"link\"` (which carries `:href`)."
  document/mark-schema)

(def text-inline-schema
  "Malli schema for a text inline node: `:type` `\"text\"`, `:text`, and optional
  `:marks` as a vector of `mark-schema`."
  document/text-inline-schema)

(def content-block-types
  "Set of the library's built-in canonical block types: `\"heading\"`, `\"paragraph\"`,
  `\"bullet-list\"`, `\"ordered-list\"`, `\"list-item\"`, `\"code-block\"`,
  `\"divider\"`."
  document/content-block-types)

(defn document-schema
  "Builds a closed canonical document schema from the library's rich-content grammar
  plus host-owned root, block, and inline extensions.

  Parameters:
  - `options` — host extension map:
    - `:root-fields` — optional seq of ordinary Malli map entries appended to the root
      map (`:schema-version`, `:id`, `:title`, and `:nodes` are always present), for
      example `[[:metadata [:map …]]]`.
    - `:block-nodes` — optional map of canonical block type string → Malli schema for
      block-node extensions the host renders.
    - `:inline-nodes` — optional map of canonical inline type string → Malli schema for
      inline-node extensions the host renders.

  Returns: a Malli schema (closed root map) validating canonical documents with the
  built-in grammar and the host extensions."
  [options]
  (document/document-schema options))

(defn all-nodes
  "Walks every node of a canonical document's node tree.

  Parameters:
  - `nodes` — canonical document node vector.

  Returns: lazy sequence of every map node, depth-first document order, parents before
  their children."
  [nodes]
  (document/all-nodes nodes))

(defn node-ids
  "Collects the `:id` of every node that has one.

  Parameters:
  - `nodes` — canonical document node vector.

  Returns: lazy sequence of node ids in depth-first document order."
  [nodes]
  (document/node-ids nodes))

(defn unique-node-ids?
  "Tests whether every node carrying an id has a document-wide unique id.

  Parameters:
  - `nodes` — canonical document node vector.

  Returns: true when no id repeats, false otherwise (nodes without ids are ignored)."
  [nodes]
  (document/unique-node-ids? nodes))

(defn find-node
  "Finds one node by id.

  Parameters:
  - `nodes` — canonical document node vector.
  - `node-id` — id of the node to find.

  Returns: the node map with that id, or `nil` when no node has it."
  [nodes node-id]
  (document/find-node nodes node-id))

(defn update-node
  "Applies a function to one node, keeping every other node and the document order.

  Parameters:
  - `nodes` — canonical document node vector.
  - `node-id` — id of the node to update.
  - `f` — function of the node map returning its replacement.

  Returns: new node vector of the same shape with `f` applied to the matching node at
  whichever depth it sits."
  [nodes node-id f]
  (document/update-node nodes node-id f))

(defn remove-node
  "Removes one node from whichever `:children` vector owns it.

  Parameters:
  - `nodes` — canonical document node vector.
  - `node-id` — id of the node to remove.

  Returns: new node vector without that node; a missing id leaves the tree unchanged."
  [nodes node-id]
  (document/remove-node nodes node-id))

(defn move-node
  "Moves a node up or down among its current siblings.

  Parameters:
  - `nodes` — canonical document node vector.
  - `node-id` — id of the node to move.
  - `direction` — `:up`, `:down`, or anything else for no movement.

  Returns: new node vector with the node swapped with its previous (`:up`) or next
  (`:down`) sibling; an invalid move, or a node without siblings, returns the tree
  unchanged."
  [nodes node-id direction]
  (document/move-node nodes node-id direction))

#?(:cljs
   (do
     (defn canonical->tiptap
       "Encodes a canonical document as Tiptap document data.

       Parameters:
       - `codecs` — host codec map:
         - `:root->attrs` — function of the canonical document returning the Tiptap
           document attributes.
         - `:block-codecs` — optional map of canonical block type string →
           `{:tiptap-type … :to-attrs … :from-attrs …}` for host block extensions.
         - `:inline-codecs` — optional map of canonical inline type string → the same
           codec shape for host inline extensions.
       - `document` — canonical document map (`:id`, `:title`, `:nodes`).

       Returns: Tiptap document map with `:type \"doc\"`, `:attrs` from `:root->attrs`,
       and `:content` holding the encoded nodes."
       [codecs document]
       (tiptap/canonical->tiptap codecs document))

     (defn tiptap->canonical
       "Decodes Tiptap document data into a canonical document.

       Parameters:
       - `codecs` — host codec map:
         - `:attrs->root` — function of the Tiptap document attributes returning the
           canonical root fields.
         - `:block-codecs` / `:inline-codecs` — optional extension codecs, as in
           `canonical->tiptap`.
       - `tiptap-document` — keyword-keyed Tiptap document map.

       Returns: canonical document map from `:attrs->root` plus `:nodes`.

       Throws `ex-info` (`{:received-type … :received-keys …}`) when the input's
       `:type` is not `\"doc\"`; string-keyed data is rejected this way instead of
       decoding into an empty document."
       [codecs tiptap-document]
       (tiptap/tiptap->canonical codecs tiptap-document))

     (defn js-tiptap->canonical
       "Decodes a Tiptap document that crossed the JS bridge into a canonical document.

       Parameters:
       - `codecs` — host codec map, as in `tiptap->canonical`.
       - `content` — Tiptap document as a raw JS value (any prototype depth).

       Returns: canonical document map.

       Throws `ex-info` like `tiptap->canonical` when the payload is not a Tiptap
       document."
       [codecs content]
       (tiptap/js-tiptap->canonical codecs content))

     (defn canonical-node->tiptap
       "Encodes one canonical block node as Tiptap node data.

       Parameters:
       - `codecs` — host codec map, as in `canonical->tiptap`.
       - `node` — canonical block node map (`:id`, `:type`, plus `:children`/`:text`
         and type-specific fields such as `:level`, `:start`, `:language`); inline
         children are encoded through `:inline-codecs`.

       Returns: Tiptap node map with `:type`, `:attrs` (including `:nodeId`), and
       `:content` where the node has children.

       Throws `ex-info` (`{:type … :id …}`) for a block type that is neither built in
       nor present in `:block-codecs`."
       [codecs node]
       (tiptap/canonical-node->tiptap codecs node))

     (defn tiptap-node->canonical
       "Decodes one Tiptap node into a canonical block node.

       Parameters:
       - `codecs` — host codec map, as in `canonical->tiptap`.
       - `node` — keyword-keyed Tiptap node map (`:type`, `:attrs`, `:content`).

       Returns: canonical node map with `:id` taken from the node's `:nodeId`
       attribute, `:type`, and `:children` (or `:text`/`:language` for code blocks).

       Throws `ex-info` (`{:type …}`) for a Tiptap node type that is neither built in
       nor present in `:block-codecs`."
       [codecs node]
       (tiptap/tiptap-node->canonical codecs node))))
