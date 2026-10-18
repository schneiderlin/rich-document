(ns com.zihao.rich-document.interface
  "Polylith interface facade for the rich-document library.

  Implementation namespaces keep their original names so existing consumers are
  unaffected; Polylith bricks address this brick only through this namespace."
  (:require
   [rich-document.core :as document]
   #?(:cljs [rich-document.tiptap :as tiptap])))

(def non-empty-string document/non-empty-string)
(def json-value document/json-value)
(def json-value? document/json-value?)
(def mark-schema document/mark-schema)
(def text-inline-schema document/text-inline-schema)
(def content-block-types document/content-block-types)
(def document-schema document/document-schema)
(def all-nodes document/all-nodes)
(def node-ids document/node-ids)
(def unique-node-ids? document/unique-node-ids?)
(def find-node document/find-node)
(def update-node document/update-node)
(def remove-node document/remove-node)
(def move-node document/move-node)

#?(:cljs
   (do
     (def canonical->tiptap tiptap/canonical->tiptap)
     (def tiptap->canonical tiptap/tiptap->canonical)
     (def canonical-node->tiptap tiptap/canonical-node->tiptap)
     (def tiptap-node->canonical tiptap/tiptap-node->canonical)))
