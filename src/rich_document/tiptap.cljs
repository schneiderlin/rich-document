(ns rich-document.tiptap
  "Canonical <-> Tiptap codecs.

  The codecs work on keyword-keyed Clojure data. The Tiptap bridge hands host
  callbacks plain JavaScript values, so this namespace also owns the JS boundary
  (`js->tiptap-data` / `js-tiptap->canonical`): decoding a bridge payload must
  not depend on each host remembering to keywordize.")

;; JS boundary
;;
;; `js->clj` is not usable here: it only descends into objects whose prototype is
;; exactly `js/Object`, so it leaves ProseMirror's attribute maps (null-prototype
;; objects) and cross-realm objects as raw JS. Worse, it silently keeps string
;; keys, and keyword destructuring of a string-keyed map yields `nil` — which
;; decoded as a valid empty document instead of failing. A bridge payload is
;; exactly where that happened, so this namespace walks the value itself.

(def ^:private object-tag "[object Object]")
(def ^:private array-tag "[object Array]")

(defn- tag-of [^js value]
  (.call (.-toString js/Object.prototype) value))

(defn- js-array? [value]
  (and (some? value) (identical? array-tag (tag-of value))))

(defn- js-object? [value]
  (and (some? value) (identical? object-tag (tag-of value))))

(defn js->tiptap-data
  "Keyword-keyed Clojure data for a Tiptap JSON value that crossed the JS boundary."
  [value]
  (cond
    (js-array? value) (mapv js->tiptap-data value)
    (js-object? value) (into {}
                             (map (fn [key]
                                    [(keyword key) (js->tiptap-data (unchecked-get value key))]))
                             (js-keys value))
    :else value))

(defn- mark->tiptap [{:keys [type href]}]
  (case type
    "inline-code" {:type "code"}
    "link" {:type "link" :attrs {:href href}}
    {:type type}))

(defn- mark->canonical [{:keys [type attrs]}]
  (case type
    "code" {:type "inline-code"}
    "link" {:type "link" :href (:href attrs)}
    {:type type}))

(defn- codec-by-tiptap-type [codecs tiptap-type]
  (some (fn [[canonical-type codec]]
          (when (= tiptap-type (:tiptap-type codec))
            [canonical-type codec]))
        codecs))

(defn inline->tiptap
  [{:keys [inline-codecs]} {:keys [type text marks] :as node}]
  (if (= "text" type)
    (cond-> {:type "text" :text text}
      (seq marks) (assoc :marks (mapv mark->tiptap marks)))
    (if-let [{:keys [tiptap-type to-attrs]} (get inline-codecs type)]
      {:type tiptap-type :attrs (to-attrs node)}
      (throw (ex-info "Unsupported Canonical inline node" {:type type})))))

(defn inline->canonical
  [{:keys [inline-codecs]} {:keys [type text marks attrs]}]
  (if (= "text" type)
    (cond-> {:type "text" :text text}
      (seq marks) (assoc :marks (mapv mark->canonical marks)))
    (if-let [[canonical-type {:keys [from-attrs]}]
             (codec-by-tiptap-type inline-codecs type)]
      (assoc (from-attrs attrs) :type canonical-type)
      (throw (ex-info "Unsupported Tiptap inline node" {:type type})))))

(declare canonical-node->tiptap)

(defn canonical-node->tiptap
  [{:keys [block-codecs] :as codecs}
   {:keys [id type level children start language text] :as node}]
  (case type
    "heading" {:type "heading" :attrs {:nodeId id :level level}
               :content (mapv #(inline->tiptap codecs %) children)}
    "paragraph" {:type "paragraph" :attrs {:nodeId id}
                 :content (mapv #(inline->tiptap codecs %) children)}
    "bullet-list" {:type "bulletList" :attrs {:nodeId id}
                   :content (mapv #(canonical-node->tiptap codecs %) children)}
    "ordered-list" {:type "orderedList" :attrs {:nodeId id :start start}
                    :content (mapv #(canonical-node->tiptap codecs %) children)}
    "list-item" {:type "listItem" :attrs {:nodeId id}
                 :content (mapv #(canonical-node->tiptap codecs %) children)}
    "code-block" {:type "codeBlock" :attrs {:nodeId id :language language}
                  :content (when (seq text) [{:type "text" :text text}])}
    "divider" {:type "horizontalRule" :attrs {:nodeId id}}
    (if-let [{:keys [tiptap-type to-attrs]} (get block-codecs type)]
      {:type tiptap-type :attrs (to-attrs node)}
      (throw (ex-info "Unsupported Canonical block node" {:type type :id id})))))

(declare tiptap-node->canonical)

(defn tiptap-node->canonical
  [{:keys [block-codecs] :as codecs} {:keys [type attrs content]}]
  (case type
    "heading" {:id (:nodeId attrs) :type "heading" :level (:level attrs)
               :children (mapv #(inline->canonical codecs %) (or content []))}
    "paragraph" {:id (:nodeId attrs) :type "paragraph"
                 :children (mapv #(inline->canonical codecs %) (or content []))}
    "bulletList" {:id (:nodeId attrs) :type "bullet-list"
                  :children (mapv #(tiptap-node->canonical codecs %) content)}
    "orderedList" {:id (:nodeId attrs) :type "ordered-list" :start (:start attrs)
                   :children (mapv #(tiptap-node->canonical codecs %) content)}
    "listItem" {:id (:nodeId attrs) :type "list-item"
                :children (mapv #(tiptap-node->canonical codecs %) content)}
    "codeBlock" {:id (:nodeId attrs) :type "code-block"
                 :language (or (:language attrs) "text")
                 :text (apply str (map :text content))}
    "horizontalRule" {:id (:nodeId attrs) :type "divider"}
    (if-let [[canonical-type {:keys [from-attrs]}]
             (codec-by-tiptap-type block-codecs type)]
      (assoc (from-attrs attrs) :type canonical-type)
      (throw (ex-info "Unsupported Tiptap block node" {:type type})))))

(defn canonical->tiptap
  [{:keys [root->attrs] :as codecs} document]
  {:type "doc"
   :attrs (root->attrs document)
   :content (mapv #(canonical-node->tiptap codecs %) (:nodes document))})

(defn tiptap->canonical
  "Canonical document for keyword-keyed Tiptap document data.

  Rejects anything that is not a Tiptap document. Without the check a mismatched
  input (e.g. string-keyed data) destructures to `nil` nodes and decodes as a
  valid-looking empty document, which is how a broken JS boundary stayed silent."
  [{:keys [attrs->root] :as codecs} {:keys [type attrs content] :as tiptap-document}]
  (when-not (= "doc" type)
    (throw (ex-info "Unsupported Tiptap document"
                    {:received-type type
                     :received-keys (vec (keys tiptap-document))})))
  (assoc (attrs->root attrs)
         :nodes (mapv #(tiptap-node->canonical codecs %) content)))

(defn js-tiptap->canonical
  "Canonical document for a Tiptap document JSON value produced by the JS bridge."
  [codecs content]
  (tiptap->canonical codecs (js->tiptap-data content)))
