(ns rich-document.tiptap-test
  (:require [cljs.test :refer-macros [deftest is]]
            [rich-document.tiptap :as tiptap]))

(def extension-codecs
  {:root->attrs
   (fn [{:keys [schema-version id title metadata]}]
     {:schemaVersion schema-version
      :documentId id
      :title title
      :metadata metadata})
   :attrs->root
   (fn [attrs]
     {:schema-version (:schemaVersion attrs)
      :id (:documentId attrs)
      :title (:title attrs)
      :metadata (:metadata attrs)})
   :inline-codecs
   {"mention"
    {:tiptap-type "mention"
     :to-attrs (fn [{:keys [id subject]}] {:nodeId id :subject subject})
     :from-attrs (fn [{:keys [nodeId subject]}] {:id nodeId :subject subject})}}
   :block-codecs
   {"callout"
    {:tiptap-type "callout"
     :to-attrs (fn [{:keys [id tone message]}]
                 {:nodeId id :tone tone :message message})
     :from-attrs (fn [{:keys [nodeId tone message]}]
                   {:id nodeId :tone tone :message message})}}})

(deftest host-extension-codecs-round-trip-with-built-in-rich-content
  (let [document {:schema-version 1
                  :id "document-1"
                  :title "Codec document"
                  :metadata {"owner" "team-a"}
                  :nodes [{:id "paragraph-1"
                           :type "paragraph"
                           :children [{:type "text" :text "Hello"
                                      :marks [{:type "bold"}]}
                                      {:id "mention-1" :type "mention"
                                       :subject "reader"}]}
                          {:id "callout-1" :type "callout"
                           :tone "warning" :message "Check this"}]}
        encoded (tiptap/canonical->tiptap extension-codecs document)]
    (is (= {:type "mention"
            :attrs {:nodeId "mention-1" :subject "reader"}}
           (get-in encoded [:content 0 :content 1])))
    (is (= {:type "callout"
            :attrs {:nodeId "callout-1" :tone "warning" :message "Check this"}}
           (get-in encoded [:content 1])))
    (is (= document (tiptap/tiptap->canonical extension-codecs encoded)))))

;; The JS boundary.
;;
;; `createRichDocumentEditor` reports the canonical projection back through
;; `options.onDocumentUpdate` as a JavaScript value, so the shape the bridge
;; actually hands a host has to be covered here. Testing only Clojure data leaves
;; the seam untested, which is how a string-keyed decode silently produced an
;; empty document in the Playbook.

(def ^:private bridge-codecs
  "Root codec pair with no extra root fields, matching how a host declares a
  document whose canonical shape is `schema-version` + `id` + `title`."
  (assoc extension-codecs
         :root->attrs (fn [{:keys [id title]}] {:documentId id :title title})
         :attrs->root (fn [attrs] {:schema-version 1
                                   :id (:documentId attrs)
                                   :title (or (:title attrs) "")})))

(def ^:private bridge-document
  {:schema-version 1
   :id "document-2"
   :title "Bridge document"
   :nodes [{:id "paragraph-1"
            :type "paragraph"
            :children [{:type "text" :text "Hello" :marks [{:type "bold"}]}
                       {:id "mention-1" :type "mention" :subject "reader"}]}
           {:id "callout-1" :type "callout" :tone "warning" :message "Check this"}]})

(defn- bridge-json
  "A Tiptap document JSON value in the shape the bridge emits: `clone` is a JSON
  round trip, so every object is a fresh plain JS object."
  [tiptap-data]
  (js/JSON.parse (js/JSON.stringify (clj->js tiptap-data))))

(deftest js-boundary-decodes-bridge-document-json
  (let [content (bridge-json (tiptap/canonical->tiptap bridge-codecs bridge-document))
        decoded (tiptap/js-tiptap->canonical bridge-codecs content)]
    (is (= "document-2" (:id decoded)))
    (is (= "Bridge document" (:title decoded)))
    (is (= 2 (count (:nodes decoded))))
    (is (= bridge-document decoded))))

(deftest js-boundary-descends-into-null-prototype-attributes
  ;; ProseMirror returns attribute maps without an object prototype, which
  ;; `js->clj` does not descend into; the boundary must decode them anyway.
  (let [content (bridge-json (tiptap/canonical->tiptap bridge-codecs bridge-document))]
    (set! (.-attrs content)
          (js/Object.assign (js/Object.create nil) (.-attrs content)))
    (is (= bridge-document (tiptap/js-tiptap->canonical bridge-codecs content)))))

(deftest rejects-data-that-is-not-a-tiptap-document
  ;; String-keyed data (the un-keywordized bridge value) used to destructure to
  ;; nil nodes and decode as a valid empty document instead of failing.
  (let [string-keyed (js->clj (clj->js (tiptap/canonical->tiptap bridge-codecs bridge-document)))]
    (is (thrown? js/Error (tiptap/tiptap->canonical bridge-codecs string-keyed)))))
