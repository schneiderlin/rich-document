(ns rich-document.tiptap)

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
  [{:keys [attrs->root] :as codecs} {:keys [attrs content]}]
  (assoc (attrs->root attrs)
         :nodes (mapv #(tiptap-node->canonical codecs %) content)))
