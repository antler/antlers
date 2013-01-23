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

(deftest this-test
  (is (= "1 14 29 316 425 5"
         (render-string "{{#yellow}}{{(* this this)}} {{this}}{{/yellow}}" {:yellow [1 2 3 4 5]}))))

(deftest spaces-test
  (is (= "55555" (render-string "{{yellow.ochre.window.chasm}}" {:yellow {:ochre {:window {:chasm 55555}}}}))))

(deftest empty-string-test
  (is (= "hello albatross" (render-string "hello {{#bbb}}WTF{{/bbb}}{{^bbb}}albatross{{/bbb}}" {:bbb ""}))))

(deftest partial-test
  (is (= "hello hello what?\n" (render-string "hello {{> [[partial-name]].html }}" {:partial-name "yellow" :hello "hello"}))))

(deftest lambda-test
  (is (= "purple YEAHEYAH WTF" (render-string "purple {{cronge}}" {:cronge #(str (:okay %) " WTF") :okay "YEAHEYAH"}))))

(deftest lambda-boolean-test
  (is (= "chartreuse nononon"
         (render-string "chartreuse {{#ipip.bul nonon.elb olol}}nononon{{/ipip.bul nonon.elb olol}}"
                        {:ipip {:bul (fn [env nonon olol] (= olol nonon))} :nonon {:elb "gold"} :olol "gold"}))))

(deftest nested-map-test
  (is
   (=
    "Hello green YES yellow"
    (render-string
     "Hello {{map-pusher params {:over \"yellow\" :under red.okay} }}"
     {:params {:green "green" :over "iiggp" :under "what"}
      :red {:okay "YES"}
      :map-pusher (fn [env params additional]
                    (let [merged (merge params additional)]
                      (str (:green merged) " " (:under merged) " " (:over merged))))}))))