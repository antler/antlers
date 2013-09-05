(ns antlers.utils
  (:require [clojure.string :as string]
            [clojure.walk :as walk]
            [quoin.map-access :as map]))

;;
;; Context stack access logic
;;
;; find-containing-context and context-get are a significant portion of
;; execution time during rendering, so they are written in a less beautiful
;; way to make them go faster.
;;

(defn find-containing-context
  "Given a context stack and a key, walks down the context stack until
   it finds a context that contains the key. The key logic is fuzzy as
   in get-named/contains-named? in quoin. Returns the context, not the
   key's value, so nil when no context is found that contains the
   key."
  [context-stack key]
  (loop [curr-context-stack context-stack]
    (if-let [context-top (peek curr-context-stack)]
      (if (try (map/contains-named? context-top key) (catch Exception e nil))
        context-top
        ;; Didn't have the key, so walk down the stack.
        (recur (next curr-context-stack)))
      ;; Either ran out of context stack or key, in either case, we were
      ;; unsuccessful in finding the key.
      nil)))

(declare ^:dynamic *locals*)

(defn eval-with-map
  "Evals a form with given locals.  The locals should be a map of symbols to
  values."
  [locals form]
  (binding [*locals* locals]
    (eval
     `(let ~(vec (mapcat #(list (symbol (name %)) `(*locals* '~%)) (keys locals)))
        ~(read-string form)))))

(defn merge-contexts
  [context]
  (let [top (first context)
        stack (filter map? (rest context))
        fused (apply merge (reverse stack))]
    (if (map? top)
      (merge fused top)
      (assoc fused :this top))))

(defn split-name
  [s]
  (doall
   (map keyword (string/split s #"\."))))

(defn split-symbol
  [s]
  (if (symbol? s)
    (split-name (str s))
    s))

(defn nested-get
  ([full-stack sym]
     (nested-get full-stack sym nil))
  ([full-stack sym not-found]
     (let [full-path (split-symbol sym)]
       (loop [stack full-stack
              path full-path]
         (if-let [matching-context (find-containing-context stack (first path))]
           (let [found (map/get-named matching-context (first path))
                 continuing (next path)]
             (if continuing
               (recur (list found) (rest path))
               found))
           not-found)))))

(defn draw-context
  [context not-found]
  (fn [leaf]
    (if (symbol? leaf)
      (if (= '| leaf)
        '|
        (nested-get context leaf not-found))
      leaf)))

(defn contextualize-tree
  [context tree not-found]
  (let [draw (draw-context context not-found)
        walked (walk/postwalk draw tree)]
    walked))

(defn apply-chain
  [chain]
  (let [source (first chain)
        value (apply (first source) (rest source))
        pipe (rest chain)]
    (reduce 
     (fn [value link]
       (let [command (first link)]
         (if (= '| command)
           value
           (let [args (cons value (rest link))]
             (apply command args)))))
     value pipe)))

(defn context-get
  "Given a context stack and key, implements the rules for getting the
   key out of the context stack (see interpolation.yml in the spec). The
   key is assumed to be either the special keyword :implicit-top, or a list of
   strings or keywords."
  ([context-stack path]
     (context-get context-stack path nil))
  ([context-stack path not-found]
     (cond
      (.equals :implicit-top path) (first context-stack) ;; .equals is faster than =

      (try (contains? path :clojure) (catch Exception e nil));; are we evaling clojure code?
      (eval-with-map (merge-contexts context-stack) (get path :clojure))

      :else
      (let [defined (contextualize-tree context-stack path not-found)
            front (first defined)]
        (if (instance? clojure.lang.Fn front)
          (let [piped (partition-by #{'|} defined)
                result (apply-chain piped)]
            result)
          front)))))

(defn call-lambda
  "Calls a lambda function, respecting the options given in its metadata, if
   any. The content arg is the content of the tag being processed as a lambda in
   the template, and the context arg is the current context at this point in the
   processing. The latter will be ignored unless metadata directs otherwise.
 
   Respected metadata:
     - :antlers/pass-context: passes the current context to the lambda as the
       second arg."
  ([lambda-fn context]
     (if (:antlers/pass-context (meta lambda-fn))
       (lambda-fn context)
       (lambda-fn)))
  ([lambda-fn content context]
      (if (:antlers/pass-context (meta lambda-fn))
        (lambda-fn content context)
        (lambda-fn content))))
