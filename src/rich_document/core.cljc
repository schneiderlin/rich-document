(ns rich-document.core
  (:require [malli.core :as m]
            [malli.registry :as mr]))

(def non-empty-string [:string {:min 1}])

(defn json-value?
  "Returns whether value can cross a JSON persistence boundary unchanged."
  [value]
  (or (nil? value)
      (string? value)
      (boolean? value)
      (number? value)
      (and (vector? value) (every? json-value? value))
      (and (map? value)
           (every? string? (keys value))
           (every? json-value? (vals value)))))

(def json-value [:fn json-value?])

(def mark-schema
  [:multi {:dispatch :type}
   ["bold" [:map {:closed true} [:type [:= "bold"]]]]
   ["italic" [:map {:closed true} [:type [:= "italic"]]]]
   ["strike" [:map {:closed true} [:type [:= "strike"]]]]
   ["inline-code" [:map {:closed true} [:type [:= "inline-code"]]]]
   ["link" [:map {:closed true}
            [:type [:= "link"]]
            [:href non-empty-string]]]])

(def text-inline-schema
  [:map {:closed true}
   [:type [:= "text"]]
   [:text string?]
   [:marks {:optional true} [:vector mark-schema]]])

(defn- multi-schema [schemas]
  (into [:multi {:dispatch :type}]
        (map (fn [[type schema]] [type schema]))
        schemas))

(defn- content-block-schemas []
  {"heading" [:map {:closed true}
              [:id non-empty-string]
              [:type [:= "heading"]]
              [:level [:int {:min 1 :max 6}]]
              [:children [:vector [:ref ::inline-node]]]]
   "paragraph" [:map {:closed true}
                [:id non-empty-string]
                [:type [:= "paragraph"]]
                [:children [:vector [:ref ::inline-node]]]]
   "bullet-list" [:map {:closed true}
                  [:id non-empty-string]
                  [:type [:= "bullet-list"]]
                  [:children [:vector [:ref ::list-item]]]]
   "ordered-list" [:map {:closed true}
                   [:id non-empty-string]
                   [:type [:= "ordered-list"]]
                   [:start int?]
                   [:children [:vector [:ref ::list-item]]]]
   "list-item" [:ref ::list-item]
   "code-block" [:map {:closed true}
                 [:id non-empty-string]
                 [:type [:= "code-block"]]
                 [:language non-empty-string]
                 [:text string?]]
   "divider" [:map {:closed true}
              [:id non-empty-string]
              [:type [:= "divider"]]]})

(def content-block-types
  #{"heading" "paragraph" "bullet-list" "ordered-list" "list-item" "code-block" "divider"})

(defn document-schema
  "Builds a closed Canonical Document schema from the rich-content grammar and
   host-owned root, block, and inline extensions. Extension maps are keyed by
   their canonical string type; root-fields contains ordinary Malli map entries."
  [{:keys [root-fields block-nodes inline-nodes]}]
  (let [content-blocks (content-block-schemas)
        inline-nodes (merge {"text" text-inline-schema} inline-nodes)
        block-nodes (merge (into {} (map (fn [type] [type [:ref ::content-block]]))
                                 content-block-types)
                           block-nodes)
        registry {::inline-node (multi-schema inline-nodes)
                  ::content-block (multi-schema content-blocks)
                  ::list-item [:map {:closed true}
                               [:id non-empty-string]
                               [:type [:= "list-item"]]
                               [:children [:vector [:ref ::content-block]]]]
                  ::block-node (multi-schema block-nodes)}
        shape (into [:map {:closed true}
                     [:schema-version [:= 1]]
                     [:id non-empty-string]
                     [:title non-empty-string]]
                    (concat root-fields
                            [[:nodes [:vector [:ref ::block-node]]]]))]
    (m/schema shape
              {:registry (mr/composite-registry (m/default-schemas) registry)})))

(defn all-nodes
  "Returns every map node in depth-first document order."
  [nodes]
  (mapcat (fn [node]
            (cons node (all-nodes (filter map? (:children node)))))
          nodes))

(defn node-ids [nodes]
  (keep :id (all-nodes nodes)))

(defn unique-node-ids?
  "Returns whether every node carrying an ID has a document-wide unique ID."
  [nodes]
  (let [ids (node-ids nodes)]
    (= (count ids) (count (distinct ids)))))

(defn find-node [nodes node-id]
  (first (filter #(= node-id (:id %)) (all-nodes nodes))))

(defn update-node
  "Applies f to the node with node-id, preserving every other node and order."
  [nodes node-id f]
  (mapv (fn [node]
          (let [node (if (= node-id (:id node)) (f node) node)]
            (cond-> node
              (:children node) (update :children #(update-node % node-id f)))))
        nodes))

(defn remove-node
  "Removes the node with node-id from whichever children vector owns it."
  [nodes node-id]
  (->> nodes
       (remove #(= node-id (:id %)))
       (mapv (fn [node]
               (cond-> node
                 (:children node) (update :children #(remove-node % node-id)))))))

(defn- move-among-siblings [nodes node-id offset]
  (let [index (first (keep-indexed #(when (= node-id (:id %2)) %1) nodes))
        target (when (some? index) (+ index offset))]
    (if (and (some? target) (<= 0 target) (< target (count nodes)))
      [(assoc nodes index (nth nodes target) target (nth nodes index)) true]
      [nodes false])))

(defn- move-node* [nodes node-id offset]
  (let [[moved changed?] (move-among-siblings nodes node-id offset)]
    (if changed?
      [moved true]
      (loop [remaining nodes
             result []]
        (if-let [node (first remaining)]
          (if-let [children (:children node)]
            (let [[next-children child-changed?] (move-node* children node-id offset)
                  next-node (if child-changed? (assoc node :children next-children) node)]
              (if child-changed?
                [(into (conj result next-node) (rest remaining)) true]
                (recur (rest remaining) (conj result node))))
            (recur (rest remaining) (conj result node)))
          [(vec result) false])))))

(defn move-node
  "Moves a node up or down among its current siblings. Invalid moves are no-ops."
  [nodes node-id direction]
  (first (move-node* nodes node-id (case direction :up -1 :down 1 0))))
