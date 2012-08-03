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

(deftest clj-test
  (is (= "6bbb"
         (render-string "{{(+ 3 3)}}{{# (> xxx 2)}}bbb{{/ (> xxx 2)}}" {:xxx 3}))))

(deftest eval-test
  (is (= "9ccc"
         (render-string "{{(* 3 3)}}{{# (= vv \"yoyo\")}}bbb{{/ (= vv \"yoyo\")}}{{^ (= vv \"yoyo\")}}ccc{{/ (= vv \"yoyo\")}}" {:xxx 3 :vv "xixix"}))))

(deftest list-test
  (is (= "12345"
         (render-string "{{# (take 5 (iterate inc 1))}}{{.}}{{/ (take 5 (iterate inc 1))}}" {}))))