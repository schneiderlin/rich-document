(ns rich-document.test-runner
  (:require [clojure.test :as test]
            [rich-document.core-test]))

(defn -main [& _]
  (let [{:keys [fail error]} (test/run-tests 'rich-document.core-test)]
    (System/exit (+ fail error))))
