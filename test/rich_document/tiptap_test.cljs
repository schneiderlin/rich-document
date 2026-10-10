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
