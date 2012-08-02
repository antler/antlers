(ns stencil.test.core
  (:use clojure.test
        stencil.core
        stencil.parser))

;; Test case to make sure we don't get a regression on inverted sections with
;; list values for a name.

(deftest inverted-section-list-key-test
  (is (= ""
         (render-string "{{^a}}a{{b}}a{{/a}}" {:a [:b "11"]})))
  (is (= ""
         (render-string "{{^a}}a{{b}}a{{/a}}" {"a" ["b" "11"]}))))


(def base "x{{%yellow}}{{/yellow}}z")

(deftest block-test
  (register-template "base" base)
  (is (= "xyz"
         (render-string "{{< base}}{{%yellow}}{{yyy}}{{/yellow}}" {:yyy "y"}))))