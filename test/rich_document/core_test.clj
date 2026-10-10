(ns rich-document.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [rich-document.core :as rich-document]))

(def extended-document-schema
  (rich-document/document-schema
   {:root-fields
    [[:metadata [:map {:closed true} [:owner rich-document/non-empty-string]]]]
    :inline-nodes
    {"mention" [:map {:closed true}
                [:id rich-document/non-empty-string]
                [:type [:= "mention"]]
                [:subject rich-document/non-empty-string]]}
    :block-nodes
    {"callout" [:map {:closed true}
                [:id rich-document/non-empty-string]
                [:type [:= "callout"]]
                [:tone [:enum "info" "warning"]]
                [:children [:vector [:map {:closed true}
                                     [:type [:= "text"]]
                                     [:text string?]]]]]}}))

(deftest document-schema-composes-host-owned-root-and-node-extensions
  (let [document {:schema-version 1
                  :id "document-1"
                  :title "Extended document"
                  :metadata {:owner "team-a"}
                  :nodes [{:id "paragraph-1"
                           :type "paragraph"
                           :children [{:type "text" :text "Hello "}
                                      {:id "mention-1" :type "mention"
                                       :subject "reader"}]}
                          {:id "callout-1"
                           :type "callout"
                           :tone "warning"
                           :children [{:type "text" :text "Check this"}]}]}]
    (is (m/validate extended-document-schema document))
    (testing "the composed schema remains closed"
      (is (false? (m/validate extended-document-schema
                              (assoc document :unexpected true)))))))

(deftest tree-operations-work-at-any-depth-without-changing-stable-ids
  (let [nodes [{:id "list" :type "bullet-list"
                :children [{:id "item-a" :type "list-item"
                            :children [{:id "paragraph-a" :type "paragraph"
                                        :children [{:type "text" :text "A"}]}]}
                           {:id "item-b" :type "list-item"
                            :children [{:id "paragraph-b" :type "paragraph"
                                        :children [{:type "text" :text "B"}]}]}]}
               {:id "tail" :type "divider"}]
        updated (rich-document/update-node nodes "paragraph-b" #(assoc % :align "end"))
        moved (rich-document/move-node updated "item-b" :up)
        removed (rich-document/remove-node moved "paragraph-a")]
    (is (= ["list" "item-a" "paragraph-a" "item-b" "paragraph-b" "tail"]
           (vec (rich-document/node-ids nodes))))
    (is (rich-document/unique-node-ids? nodes))
    (is (false? (rich-document/unique-node-ids?
                 (conj nodes {:id "tail" :type "divider"}))))
    (is (= "end" (:align (rich-document/find-node updated "paragraph-b"))))
    (is (= ["item-b" "item-a"]
           (mapv :id (:children (rich-document/find-node moved "list")))))
    (is (nil? (rich-document/find-node removed "paragraph-a")))
    (is (= #{"list" "item-a" "item-b" "paragraph-b" "tail"}
           (set (rich-document/node-ids removed))))
    (testing "an invalid sibling move is a no-op"
      (is (= moved (rich-document/move-node moved "item-b" :up))))))
