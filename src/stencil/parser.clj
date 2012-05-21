(ns stencil.parser
  (:refer-clojure :exclude [partial load])
  (:require [stencil.scanner :as scan]
            [clojure.zip :as zip]
            [clojure.string :as string])

  (:import java.util.regex.Pattern)
  (:import java.util.Date)

  (:use [stencil ast re-utils utils]
        [clojure.java.io :only [resource]]
        clojure.pprint
        [slingshot.slingshot :only [throw+]]))

;;
;; Settings and defaults.
;;

;; These tags, when used standalone (only content on a line, excluding
;; whitespace before the tag), will cause all whitespace to be removed from
;; the line.
(def standalone-tag-sigils #{\# \^ \/ \< \> \= \! \%})

;; These tags will allow anything in their content.
(def freeform-tag-sigils #{\! \=})

(defn closing-sigil
  "Given a sigil (char), returns what its closing sigil could possibly be."
  [sigil]
  (if (= \{ sigil)
    \}
    sigil))

(def valid-tag-content #"(\w|[?!/.%-])*")

(def parser-defaults {:tag-open "{{" :tag-close "}}"})

;; The main parser data structure. The only tricky bit is the output, which is
;; a zipper. The zipper is kept in a state where new things are added with
;; append-child. This means that the current loc in the zipper is a branch
;; vector, and the actual "next location" is enforced in the code through using
;; append-child, and down or up when necessary due to the creation of a section.
;; This makes it easier to think of sections as being a stack.
(defrecord Parser [scanner       ;; The current scanner state.
                   output        ;; Current state of the output (a zipper).
                   state])       ;; Various options as the parser progresses.

(defn parser
  ([scanner]
     (parser scanner (ast-zip [])))
  ([scanner output]
     (parser scanner output parser-defaults))
  ([scanner output state]
     (Parser. scanner output state)))

(defn get-line-col-from-index
  "Given a string and an index into the string, returns which line of text
   the position is on. Specifically, returns an index containing a pair of
   numbers, the row and column."
  [s idx]
  (if (> idx (count s))
    (throw+ java.lang.IndexOutOfBoundsException))
  (loop [lines 0
         last-line-start 0 ;; Index in string of the last line beginning seen.
         i 0]
    (cond (= i idx) ;; Reached the index, return the number of lines we saw.
          [(inc lines) (inc (- i last-line-start))] ;; Un-zero-index.
          (= "\n" (subs s i (+ 1 i)))
          (recur (inc lines) (inc i) (inc i))
          :else
          (recur lines last-line-start (inc i)))))

(defn format-location
  "Given either a scanner or a string and index into the string, return a
   message describing the location by row and column."
  ([^stencil.scanner.Scanner sc]
     (format-location (:src sc) (scan/position sc)))
  ([s idx]
     (let [[line col] (get-line-col-from-index s idx)]
       (str "line " line ", column " col))))

(defn write-string-to-output
  "Given a zipper and a string, adds the string to the zipper at the current
   cursor location (as zip/append-child would) and returns the new zipper. This
   function will collate adjacent strings and remove empty strings, so use it
   when adding strings to a parser's output."
  [zipper ^String s]
  (let [preceding-value (-> zipper zip/down zip/rightmost)]
    (cond (empty? s) ;; If the string is empty, just throw it away!
          zipper
          ;; Otherwise, if the value right before the one we are trying to add
          ;; is also a string, we should replace the existing value with the
          ;; concatenation of the two.
          (and preceding-value
               (string? (zip/node preceding-value)))
          (-> zipper zip/down zip/rightmost
              (zip/replace (str (zip/node preceding-value) s)) zip/up)
          ;; Otherwise, actually append it.
          :else
          (-> zipper (zip/append-child s)))))

(defn tag-position?
  "Takes a scanner and returns true if it is currently in \"tag position.\"
   That is, if the only thing between it and the start of a tag is possibly some
   non-line-breaking whitespace padding."
  [^stencil.scanner.Scanner s parser-state]
  (let [tag-open-re (re-concat #"([ \t]*)?"
                               (re-quote (:tag-open parser-state)))]
    ;; Return true if first expr makes progress.
    (not= (scan/position (scan/scan s tag-open-re))
          (scan/position s))))

(defn parse-tag-name
  "This function takes a tag name (string) and parses it into a run-time data
   structure useful during rendering of the templates. Following the rules of
   mustache, it checks for a single \".\", which indicates the implicit
   iterator. If not, it splits it on periods, returning a list of
   the pieces. See interpolation.yml in the spec."
  [^String s]
  (if (= "." s)
    :implicit-top
    (string/split s #"\.")))

(defn parse-text
  "Given a parser that is not in tag position, reads text until it is and
   appends it to the output of the parser."
  [^Parser p]
  (let [scanner (:scanner p)
        state (:state p)
        ffwd-scanner (scan/skip-to-match-start
                      scanner
                      ;; (?m) is to turn on MULTILINE mode for the pattern. This
                      ;; will make it so ^ matches embedded newlines and not
                      ;; just the start of the input string.
                      (re-concat #"(?m)(^[ \t]*)?"
                                 (re-quote (:tag-open state))))
        text (subs (:src scanner)
                   (scan/position scanner)
                   (scan/position ffwd-scanner))]
    (if (nil? (:match ffwd-scanner))
      ;; There was no match, so the remainder of input is plain text.
      ;; Jump scanner to end of input and add rest of text to output.
      (parser (scan/scanner (:src scanner) (count (:src scanner)))
              (write-string-to-output (:output p) (scan/remainder scanner))
              state)
      ;; Otherwise, add the text chunk we found.
      (parser ffwd-scanner
              (write-string-to-output (:output p) text)
              state))))

(declare load-template)

(defn find-block
  [output block]
  (loop [loc (zip/seq-zip (zip/root output))]
    (println loc)
    (println block)
    (println (class loc))
    (cond
     (zip/end? loc) false
     (= (:name block) (:name (zip/node loc)))
     (zip/edit loc (fn [node] block))
     ;; (zip/replace loc block)
     ;; (zip/root (zip/replace loc block))
     :else (recur (zip/next loc)))))

;; find and replace any block of this name already in the zipper
;; with the contents of this block!  otherwise just add the
;; contents of this block where it appears.
(defn insert-block
  [output block]
  (println "finding block")
  (if-let [found (find-block output block)]
    found
    (-> output
        (zip/append-child block)
        zip/down
        zip/rightmost)))

(defn parse-tag
  "Given a parser that is in tag position, reads the next tag and appends it
   to the output of the parser with appropriate processing."
  [^Parser p]
  (let [{:keys [scanner output state]} p
        beginning-of-line? (scan/beginning-of-line? scanner)
        tag-position-scanner scanner ;; Save the original scanner, might be used
        ;; in closing tags to get source code.
        ;; Skip and save any leading whitespace.
        padding-scanner (scan/scan scanner
                                   #"([ \t]*)?")
        padding (second (scan/groups padding-scanner))
        tag-start-scanner (scan/scan padding-scanner
                                     (re-quote (:tag-open state)))
        ;; Identify the sigil (and then eat any whitespace).
        sigil-scanner (scan/scan tag-start-scanner
                                 #"#|\^|\/|=|!|<|>|%|&|\{")
        sigil (first (scan/matched sigil-scanner)) ;; first gets the char.
        sigil-scanner (scan/scan sigil-scanner #"\s*")
        ;; Scan the tag content, taking into account the content allowed by
        ;; this type of tag.
        tag-content-scanner (if (freeform-tag-sigils sigil)
                              (scan/skip-to-match-start
                               sigil-scanner
                               (re-concat #"\s*"
                                          (re-quote (closing-sigil sigil)) "?"
                                          (re-quote (:tag-close state))))
                              ;; Otherwise, restrict tag content.
                              (scan/scan sigil-scanner
                                         valid-tag-content))
        tag-content (subs (:src scanner)
                          (scan/position sigil-scanner)
                          (scan/position tag-content-scanner))
        ;; Finish the tag: any trailing whitespace, closing sigils, and tag end.
        ;; Done separately so they can succeed/fail independently.
        tag-content-scanner (scan/scan (scan/scan tag-content-scanner #"\s*")
                                       (re-quote (closing-sigil sigil)))
        close-scanner (scan/scan tag-content-scanner
                                 (re-quote (:tag-close state)))
        ;; Check if the line end comes right after... if this is a "standalone"
        ;; tag, we should remove the padding and newline.
        trailing-newline-scanner (scan/scan close-scanner #"\r?\n|$")
        strip-whitespace? (and beginning-of-line?
                               (standalone-tag-sigils sigil)
                               (not (nil? (:match trailing-newline-scanner))))
        ;; Go ahead and add the padding to the current state now, if we should.
        p (if strip-whitespace?
            (parser trailing-newline-scanner ;; Which has moved past newline...
                    output state)
            ;; Otherwise, need to add padding to output and leave parser with
            ;; a scanner that is looking at what came right after closing tag.
            (parser close-scanner
                    (write-string-to-output output padding)
                    state))
        {:keys [scanner output state]} p]
    ;; First, let's analyze the results and throw any errors necessary.
    (cond (empty? tag-content)
          (throw+ {:type :illegal-tag-content
                   :tag-content tag-content
                   :scanner tag-content-scanner}
                  (str "Illegal content in tag: " tag-content
                       " at " (format-location tag-content-scanner)))
          (nil? (:match close-scanner))
          (throw+ {:type :unclosed-tag
                   :tag-content tag-content
                   :scanner close-scanner}
                  (str "Unclosed tag: " tag-content
                       " at " (format-location close-scanner))))
    (case sigil
      (\{ \&) (parser scanner
                      (zip/append-child output
                                        (unescaped-variable
                                         (parse-tag-name tag-content)))
                      state)
      \%
      (let [b (block (parse-tag-name tag-content)
                     {:content-start
                      (scan/position (if strip-whitespace?
                                       trailing-newline-scanner
                                       close-scanner))
                      :tag-open (:tag-open state)
                      :tag-close (:tag-close state)}
                     [])]
        (parser scanner
                (insert-block output b)
                state))
      \# (parser scanner
                 (-> output
                     (zip/append-child
                      (section (parse-tag-name tag-content)
                               {:content-start
                                ;; Need to respect whether to strip white-
                                ;; space in the source.
                                (scan/position (if strip-whitespace?
                                                 trailing-newline-scanner
                                                 close-scanner))
                                ;; Lambdas in sections need to parse with
                                ;; current delimiters.
                                :tag-open (:tag-open state)
                                :tag-close (:tag-close state)}
                               []))
                     zip/down zip/rightmost)
                 state)
      \^ (parser scanner
                 (-> output
                     (zip/append-child
                      (inverted-section (parse-tag-name tag-content)
                                        {:content-start
                                         (scan/position
                                          (if strip-whitespace?
                                            trailing-newline-scanner
                                            close-scanner))}
                                        []))
                     zip/down zip/rightmost)
                 state)
      \/ (let [top-section (zip/node output)] ;; Do consistency checks...
           (if (not= (:name top-section) (parse-tag-name tag-content))
             (throw+ {:type :mismatched-closing-tag
                      :tag-content tag-content
                      :scanner tag-content-scanner}
                     (str "Attempt to close section out of order: "
                          tag-content
                          " at "
                          (format-location tag-content-scanner)))
             ;; Going to close it by moving up the zipper tree, but first
             ;; we need to store the source code between the tags so that
             ;; it can be used in a lambda.
             (let [content-start (:content-start (-> output
                                                     zip/node
                                                     :attrs))
                   ;; Where the content ends depends on whether we are
                   ;; stripping whitespace from the current tag.
                   content-end (scan/position (if strip-whitespace?
                                                tag-position-scanner
                                                padding-scanner))
                   content (subs (:src scanner) content-start content-end)]
               (parser scanner
                       ;; We need to replace the current zip node with
                       ;; one with the attrs added to its attrs field.
                       (-> output
                           (zip/replace
                            (assoc (zip/node output)
                              :attrs
                              (merge (:attrs (zip/node output))
                                     {:content-end content-end
                                      :content content})))
                           zip/up)
                       state))))
      ;; Just ignore comments.
      \! p
      \> (parser scanner
                 (-> output
                     ;; A standalone partial instead holds onto its
                     ;; padding and uses it to indent its sub-template.
                     (zip/append-child (partial tag-content
                                                (if strip-whitespace?
                                                  padding))))
                 state)

      \< (parser scanner
                 (-> output
                     (zip/append-child (load-template tag-content)))
                 state)
      
      \@ (parser scanner)

      ;; Set delimiters only affect parser state.
      \= (let [[tag-open tag-close]
               (drop 1 (re-matches #"([\S|[^=]]+)\s+([\S|[^=]]+)"
                                   tag-content))]
           (parser scanner
                   output
                   (assoc state :tag-open tag-open :tag-close tag-close)))
      ;; No sigil: it was an escaped variable reference.
      (parser scanner
              (zip/append-child output
                                (escaped-variable (parse-tag-name
                                                   tag-content)))
              state))))

(defn parse
  ([template-string]
     (parse template-string parser-defaults))
  ([template-string parser-state]
     (loop [p (parser (scan/scanner template-string)
                      (ast-zip [])
                      parser-state)]
       (let [s (:scanner p)]
         (cond
          ;; If we are at the end of input, return the output.
          (scan/end? s)
          (let [output (:output p)]
            ;; If we can go up from the zipper's current loc, then there is an
            ;; unclosed tag, so raise an error.
            (if (zip/up output)
              (throw+ {:type :unclosed-tag
                       :scanner s}
                      (str "Unclosed section: "
                           (second (zip/node output))
                           " at " (format-location s)))
              (zip/root output)))
          ;; If we are in tag-position, read a tag.
          (tag-position? s (:state p))
          (recur (parse-tag p))
          ;; Otherwise, we must have some text to read. Read until next line.
          :else
          (recur (parse-text p)))))))


;; --------------------------------------------------------------------
;; This is what was once loader.clj, except we needed to refer to `load-template`
;; from inside `parse` (for extends and blocks).  So we brought it in here!
;; Also, `load-template` used to be called `load`, but this was deemed too
;; egregious a shadowing of a core clojure function.
;; ------------------------------------------------------------------------

;; The dynamic template store just maps a template name to its source code.
(def ^{:private true} dynamic-template-store (atom {}))

;; The parsed template cache maps template names to its parsed versions.
(def ^{:private true} parsed-template-cache (atom {}))

;;
;; Cache policies
;;

(defn cache-forever
  "This cache policy will let entries live on forever (until explicitly
   invalidated). Could be useful in production if mustache templates can't be
   changed in that environment."
  [cache-entry]
  true)

(defn cache-never
  "This cache policy will consider cache entries to never be valid, essentially
   disabling caching. Could be useful for development."
  [cache-entry]
  false)

(defn cache-timeout
  "This is a cache policy generator. Takes a timeout in milliseconds as an
   argument and returns a cache policy that considers cache entries valid for
   only that long."
  [timeout-ms]
  (fn [cache-entry]
    (let [now (Date.)]
      (< (.getTime now)
         (+ (.getTime ^Date (:entry-date cache-entry))
            timeout-ms)))))

;; Cache policy dictates when a given cache entry is valid. It should be a
;; function that takes an entry and returns true if it is still valid.
;; By default, caches templates for 5 seconds.
(def ^{:private true} cache-policy (atom (cache-timeout 5000)))

;; Holds a cache entry
(defrecord TemplateCacheEntry [src          ;; The source code of the template
                               parsed       ;; Parsed ASTNode structure.
                               entry-date]) ;; Date when we cached this.

(defn template-cache-entry
  "Given template source, parsed ASTNodes, and timestamp, creates a cache entry.
   If only source is given, parsed and timestamp are calculated automatically."
  ([src]
     (template-cache-entry src (parse src)))
  ([src parsed]
     (template-cache-entry src parsed (Date.)))
  ([src parsed timestamp]
     (TemplateCacheEntry. src parsed timestamp)))

(declare invalidate-cache-entry invalidate-cache)

(defn register-template
  "Allows one to register a template in the dynamic template store. Give the
   template a name and provide its content as a string."
  [template-name content-string]
  (swap! dynamic-template-store assoc-fuzzy template-name content-string)
  (invalidate-cache-entry template-name))

(defn unregister-template
  "Removes the template with the given name from the dynamic template store."
  [template-name]
  (swap! dynamic-template-store dissoc-fuzzy template-name)
  (invalidate-cache-entry template-name))

(defn unregister-all-templates
  "Clears the dynamic template store. Also necessarily clears the template
   cache."
  []
  (reset! dynamic-template-store {})
  (invalidate-cache))

(defn find-file
  "Given a name of a mustache template, attempts to find the corresponding
   file. Returns a URL if found, nil if not. First tries to find
   filename.mustache on the classpath. Failing that, looks for filename on the
   classpath. Note that you can use slashes as path separators to find a file
   in a subdirectory."
  [template-name]
  (if-let [file-url (resource (str template-name ".mustache"))]
    file-url
    (if-let [file-url (resource template-name)]
      file-url)))

;;
;; Cache mechanics
;;
;; The template cache has two keys, the template name, and a secondary key that
;; is called the variant. The default variant is set/fetched with nil as the
;; variant key. Invalidating an entry invalidates all variants. The variants
;; do NOT work with "fuzzy" map logic for getting/setting.
;;

(defn cache-assoc
  "Function used to make atomic updates to the cache. Inserts val at the
   hierarchical position in the map given by the pair of keys template-name and
   template-variant. The first key (template-name) is fuzzy, the variant is
   not."
  [map [template-name template-variant] val]
  (let [template-variants (get-fuzzy map template-name)]
    (assoc-fuzzy map template-name (assoc template-variants
                                     template-variant val))))

(defn cache
  "Given a template name, variant key, template source, and parsed AST,
   stores that entry in the template cache. Returns the parsed template"
  ([template-name template-variant template-src]
     (cache template-name template-variant template-src (parse template-src)))
  ([template-name template-variant template-src parsed-template]
     (swap! parsed-template-cache
            cache-assoc [template-name template-variant]
            (template-cache-entry template-src
                                  parsed-template))
     parsed-template))

(defn invalidate-cache-entry
  "Given a template name, invalidates the cache entry for that name, if there
   is one."
  [template-name]
  (swap! parsed-template-cache dissoc-fuzzy template-name))

(defn invalidate-cache
  "Clears all entries out of the cache."
  []
  (reset! parsed-template-cache {}))

(defn cache-get
  "Given a template name, attempts to fetch the template with that name from
   the template cache. Will apply the cache policy, so if the cache policy says
   the entry is too old, it will return nil. Otherwise, returns the
   cache-entry. Single argument version gets the default (nil) variant."
  ([template-name]
     (cache-get template-name nil))
  ([template-name template-variant]
     (let [cache-entry (get (get-fuzzy @parsed-template-cache template-name)
                            template-variant)]
       (when (and (not (nil? cache-entry))
                  (@cache-policy cache-entry))
         cache-entry))))

(defn set-cache-policy
  "Sets the function given as an argument to be the cache policy function (takes
   a cache-entry as argument, returns true if it is still valid)."
  [new-cache-policy-fn]
  (reset! cache-policy new-cache-policy-fn))

;;
;; Loader API
;;

(defn load-template
  "Attempts to load a mustache template by name. When given something like
   \"myfile\", it attempts to load the mustache template called myfile. First it
   will look in the dynamic template store, then look in the classpath for
   a file called myfile.mustache or just myfile.

   With addition arguments template-variant and variant-fn, supports the load
   and caching of template variants. The template-variant arg is a variant key,
   while the variant-fn arg is a single argument function that will be called
   with the template source as argument before it is cached or returned."
  ([template-name]
     (load-template template-name nil identity))
  ([template-name template-variant variant-fn]
     (if-let [cached (cache-get template-name template-variant)]
       (:parsed cached)
       ;; It wasn't cached, so we have to load it. Try dynamic store first.
       (if-let [dynamic-src (get-fuzzy @dynamic-template-store template-name)]
         ;; If found, parse and cache it, then return it.
         (cache template-name template-variant (variant-fn dynamic-src))
         ;; Otherwise, try to load it from disk.
         (if-let [file-url (find-file template-name)]
           (let [template-src (slurp file-url)]
             (cache template-name
                    template-variant
                    (variant-fn template-src))))))))

(extend-protocol ASTNode
  stencil.ast.Partial
  (render [this sb context-stack]
    (let [padding (:padding this)
          template (if padding
                     (load-template (:name this) padding #(indent-string % padding))
                     (load-template (:name this)))]
      (when template
        (render template sb context-stack)))))

