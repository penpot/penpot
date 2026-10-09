;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.common.tokenscript.processor
  "Resolves a set of design tokens whose values reference each other.

    (build {\"a\" {:value \"8\"} \"b\" {:value \"{a} * 2\"}} config)
    ;; => {:tokens {\"a\" #Number 8 \"b\" #Number 16} :issues nil :graph {..} :state ..}

  Resolution is a memoized depth first walk over the reference graph:
  a token is resolved by first resolving everything it references, so
  the evaluation order falls out of the recursion and cycles are detected
  by the set of tokens currently being resolved.

  References can point at:

    - another token (`{color.primary}`)
    - a field of a structured token (`{shadow.blur}`), resolved as a
      virtual token of its own
    - a group prefix (`{color}`), which yields a dictionary of the
      expression tokens directly below the prefix
    - a key of a token whose value is a dictionary (`{theme.dark}`)

  Errors never throw: they are stored per token in the issues map as
  `app.common.tokenscript.errors` maps, and tokens depending on a failed
  token get a `:processor/dependency-error` describing the chain.

  The returned `:state` supports incremental operations (`update-token`,
  `create-token`, `delete-token`, `resolve-value`) that only re-resolve
  tokens affected by the change."
  (:require
   [app.common.tokenscript.ast :as ast]
   [app.common.tokenscript.config :as config]
   [app.common.tokenscript.errors :as err]
   [app.common.tokenscript.interpreter :as interp]
   [app.common.tokenscript.lexer :as lex]
   [app.common.tokenscript.parser :as parser]
   [app.common.tokenscript.symbols :as sym]
   [app.common.tokenscript.token-data :as td]
   [app.common.tokenscript.validation :as validation]
   [clojure.string :as str]))

;; Issues ---------------------------------------------------------------------

(defn issue
  "A non error issue (validation problem, invalid name)."
  [code severity message token-name data]
  {:type :tokenscript/issue
   :code code
   :severity severity
   :message message
   :token-name token-name
   :data data})

(defn error-issue?
  "Issues that make a token unusable: language errors and `:error`
  severity issues."
  [i]
  (or (err/error-map? i) (= :error (:severity i))))

(defn issue-code
  [i]
  (:code i))

(defn- dependency-error
  "Error for `token-name` caused by its dependency `dep` failing with
  `cause`, carrying the full chain back to the root cause."
  [token-name dep cause]
  (let [[chain root-cause] (if (= :processor/dependency-error (:code cause))
                             [(into [token-name] (str/split (str (get-in cause [:data :chain])) #" → "))
                              (get-in cause [:data :root-cause])]
                             [[token-name dep] (:message cause)])]
    (err/error-map :processor/dependency-error
                   {:data {:token-name token-name
                           :chain (str/join " → " chain)
                           :root-cause root-cause}})))

(defn- error->field-issue
  "Error of a structured token field reported on the parent token with
  the field path."
  [error parent field-path]
  (let [path (td/parse-field-path field-path)]
    (cond-> {:type :tokenscript/issue
             :code (:code error)
             :severity :error
             :message (if (seq path)
                        (str "Error at " (str/join "." (map str path)) ": " (:message error))
                        (:message error))
             :token-name parent
             :path path
             :data (:data error)}
      (:line error) (assoc :line (:line error)))))

(defn- exception->error
  [e]
  (or (err/->error-map e)
      (err/error-map :processor/unknown-parsing-error {:data {:error (err/describe e)}})))

;; State ----------------------------------------------------------------------

(defn- evaluate-constants
  [cfg]
  (into {}
        (map (fn [[name raw]]
               [(str/lower-case name)
                (if (string? raw)
                  (try
                    (let [{:keys [ast]} (parser/parse raw)
                          result (when ast (interp/interpret ast {:config cfg}))]
                      (cond
                        (nil? result) (sym/data->symbol raw)
                        (string? result) (sym/data->symbol result)
                        :else result))
                    (catch #?(:clj Throwable :cljs :default) _
                      (sym/data->symbol raw)))
                  (sym/data->symbol raw))]))
        (:inline-constants cfg)))

;; Constants depend only on the config, which is shared by every build
;; of an application, so the evaluated symbols of the last config are kept.
(defonce ^:private constants-cache (volatile! nil))

(defn- constant-symbols
  "Symbols for the inline constants of the config, evaluated once per
  config."
  [cfg]
  (let [[cached-cfg symbols] @constants-cache]
    (if (identical? cached-cfg cfg)
      symbols
      (let [symbols (evaluate-constants cfg)]
        (vreset! constants-cache [cfg symbols])
        symbols))))

(defn- mutable-symbol?
  [s]
  (or (sym/list? s) (sym/dictionary? s) (sym/token? s)))

(defn- initial-state
  [tokens cfg parsers]
  (let [constants (constant-symbols cfg)]
    {:config cfg
     :parsers parsers
     :tokens tokens
     :constants constants
     ;; Mutable constants have to be copied for every evaluation (see
     ;; `constants-table`); checked once per build instead of per token.
     :mutable-constants? (boolean (some mutable-symbol? (vals constants)))
     :parsed {}            ; name -> {:ast :deps}
     :sub-fields {}        ; field path -> {:parent :field-path}
     :structured {}        ; name -> {:fields [[path expr]]}
     :prefixes {}          ; prefix -> #{member token}
     :direct-children {}   ; prefix -> #{short name}
     :graph {}             ; name -> #{dep}
     :resolved {}          ; name -> symbol | error map
     :cache {}             ; reference path -> value
     :issues {}            ; name -> [issue]
     :missing #{}
     :visiting #{}}))

(defn- add-issue
  [st name i]
  (update-in st [:issues name] (fnil conj []) i))

(defn- add-error
  "Records an error for a token or sub-field. Sub-field errors are stored
  on their parent with the field path."
  [st name error]
  (if-let [{:keys [parent field-path]} (get-in st [:sub-fields name])]
    (add-issue st parent (error->field-issue error parent field-path))
    (add-issue st name error)))

(defn- lint
  "Validates a resolved token value against its registered type."
  [st name value]
  (if (contains? (:sub-fields st) name)
    st
    (if-let [type (get-in st [:tokens name :type])]
      (reduce (fn [st i] (add-issue st name i))
              st
              (validation/type-issues (:config st) name type value))
      st)))

(defn- resolve-early
  "Stores a value known without evaluation (no-op for seeded values)."
  [st name value]
  (if (contains? (:resolved st) name)
    (update st :graph assoc name #{})
    (-> st
        (assoc-in [:resolved name] value)
        (assoc-in [:cache name] value)
        (lint name value)
        (update :graph assoc name #{}))))

(defn- fail-early
  [st name error]
  (-> st
      (assoc-in [:resolved name] error)
      (add-error name error)
      (update :graph assoc name #{})))

;; Parsing phase --------------------------------------------------------------

(defn- statement-syntax-error?
  "Inline parsing failures caused by statement keywords are retried in
  script mode."
  [e]
  (and (err/kind? :parser e)
       (or (= :parser/unallowed-inline-syntax (err/code e))
           (and (= :parser/unexpected-token (err/code e))
                (contains? lex/script-only-statement-keywords (get (err/data e) :token))))))

(defn- try-parse
  "Parses an expression in inline mode, falling back to script mode for
  statements. Returns `{:ast :references}` or `{:error error-map}`."
  [value]
  (try
    (parser/parse value {:mode :inline})
    (catch #?(:clj Throwable :cljs :default) e
      (if (statement-syntax-error? e)
        (try
          (parser/parse value)
          (catch #?(:clj Throwable :cljs :default) e
            {:error (exception->error e)}))
        {:error (exception->error e)}))))

(defn- prefixes-of
  "`\"a.b.c\"` -> `[\"a\" \"a.b\"]`"
  [name]
  (let [parts (str/split name #"\.")]
    (mapv #(str/join "." (take % parts)) (range 1 (count parts)))))

(defn- trim-trailing-dots
  [s]
  (loop [end (count s)]
    (if (and (pos? end) (= "." (subs s (dec end) end)))
      (recur (dec end))
      (subs s 0 end))))

(defn- index-prefixes
  "Builds `:prefixes` (prefix -> member tokens) and `:direct-children`
  (prefix -> short names) for the parsed expression tokens in one pass.
  The prefixes of a name are those of `prefixes-of`."
  [st]
  (let [parsed (:parsed st)
        [prefixes children]
        (reduce-kv
         (fn [[prefixes children :as acc] name _]
           (if-not (contains? parsed name)
             acc
             (let [trimmed (trim-trailing-dots name)]
               (loop [from 0 prefixes prefixes children children]
                 (if-let [dot (str/index-of trimmed "." from)]
                   (let [prefix (subs name 0 dot)
                         short (subs name (inc dot))
                         prefixes (assoc! prefixes prefix (conj! (or (get prefixes prefix) (transient #{})) name))
                         children (if (str/includes? short ".")
                                    children
                                    (assoc! children prefix (conj! (or (get children prefix) (transient #{})) short)))]
                     (recur (inc dot) prefixes children))
                   [prefixes children])))))
         [(transient {}) (transient {})]
         (:tokens st))
        persist (fn [m] (persistent! (reduce-kv (fn [m k v] (assoc! m k (persistent! v))) (transient {}) (persistent! m))))]
    (assoc st
           :prefixes (persist prefixes)
           :direct-children (persist children))))

;; Parsed values are cached across builds: the result only depends on the
;; text, and penpot resolves the same values again on every set or theme
;; change and on every edit. Entries are immutable data and shared.
(def ^:private max-cached-parses 50000)

(defonce ^:private parse-cache
  #?(:clj (java.util.concurrent.ConcurrentHashMap.)
     :cljs (js/Map.)))

(defn- parse-entry
  [value]
  (let [{:keys [ast references error]} (try-parse value)]
    (if error
      {:error error}
      {:ast ast
       :parsed {:ast ast :deps (vec (sort references))}
       :deps (set references)})))

(defn- cached-parse
  [value]
  (let [cached #?(:clj (.get ^java.util.concurrent.ConcurrentHashMap parse-cache value)
                  :cljs (.get parse-cache value))]
    (if (some? cached)
      cached
      (let [entry (parse-entry value)]
        #?(:clj (let [^java.util.concurrent.ConcurrentHashMap cache parse-cache]
                  (when (>= (.size cache) max-cached-parses) (.clear cache))
                  (.put cache value entry))
           :cljs (do (when (>= (.-size parse-cache) max-cached-parses) (.clear parse-cache))
                     (.set parse-cache value entry)))
        entry))))

(defn- parse-expression
  "Parses `value` for `name` (a token or a sub-field), recording the AST
  and dependencies or the error."
  [st name value]
  (let [{:keys [ast parsed deps error]} (cached-parse value)]
    (cond
      error (fail-early st name error)
      (nil? ast) (resolve-early st name "")
      :else (-> st
                (assoc-in [:parsed name] parsed)
                (update :graph assoc name deps)))))

(defn- parse-structured
  [st name {:keys [value type metadata]}]
  (let [fields (td/string-fields value name (:parsers st))]
    (if (empty? fields)
      (resolve-early st name (td/structured->symbol value (or type "unknown") metadata (:parsers st)))
      (let [st (reduce (fn [st [path expr]]
                         (-> st
                             (assoc-in [:sub-fields path]
                                       {:parent name :field-path (let [rest (subs path (count name))]
                                                                   (if (str/starts-with? rest ".") (subs rest 1) rest))})
                             (parse-expression path expr)))
                       st
                       fields)]
        (-> st
            (assoc-in [:structured name] {:fields fields})
            (update :graph assoc name (set (map first fields))))))))

(defn- parse-token
  [st name {:keys [value] :as data}]
  (let [st (if (td/valid-token-path? name)
             st
             (add-issue st name (issue "INVALID_TOKEN_NAME" :error
                                       (str "token \"" name "\" contains invalid characters in name (spaces, braces, or brackets are not allowed)")
                                       name {:path name})))]
    (cond
      (and (string? value) (contains? lex/uninterpreted-keyword-set value))
      (resolve-early st name (sym/string value))

      (number? value) (resolve-early st name (sym/number value))
      (boolean? value) (resolve-early st name (sym/boolean value))
      (nil? value) (resolve-early st name (sym/null))
      (or (map? value) (sequential? value)) (parse-structured st name data)
      :else (parse-expression st name (str value)))))

(defn- parse-all
  [st]
  (-> (reduce-kv parse-token st (:tokens st))
      (index-prefixes)))

;; Resolution phase -----------------------------------------------------------

(declare resolve-name)

(defn- token?
  [st name]
  (contains? (:tokens st) name))

(defn- parent-token
  "Longest token name that is a dot prefix of `path`."
  [st path]
  (some #(when (token? st %) %) (reverse (prefixes-of path))))

(defn- resolve-prefix
  "Resolves all tokens under `prefix` and caches a dictionary of the
  expression tokens directly below it."
  [st prefix]
  (let [st (reduce resolve-name st (sort (get-in st [:prefixes prefix])))
        entries (into (sym/value (sym/dictionary nil))
                      (keep (fn [short]
                              (let [path (str prefix "." short)]
                                (when (contains? (:cache st) path)
                                  (let [v (get-in st [:cache path])]
                                    [short (sym/clone-if-mutable (sym/data->symbol v))])))))
                      (sort (get-in st [:direct-children prefix])))]
    (if (seq entries)
      (assoc-in st [:cache prefix] (sym/dictionary entries))
      st)))

(defn- resolve-virtual-child
  "Resolves `{parent.key}` for a token `parent` whose value is a
  dictionary."
  [st path parent]
  (let [st (resolve-name st parent)
        parent-value (get-in st [:resolved parent])]
    (cond
      (contains? (:cache st) path) st

      (err/error-map? parent-value)
      (assoc-in st [:resolved path] (dependency-error path parent parent-value))

      ;; Only direct keys of a dictionary token are reachable, like the
      ;; one level flattening of the other implementations.
      (sym/dictionary? parent-value)
      (let [key (subs path (inc (count parent)))
            v (when-not (str/includes? key ".") (get (sym/value parent-value) key))]
        (if (some? v)
          (assoc-in st [:cache path] (sym/clone-if-mutable v))
          (assoc-in st [:resolved path] (err/error-map :processor/token-not-found {:data {:token-name path}}))))

      :else
      (assoc-in st [:resolved path] (err/error-map :processor/token-not-found {:data {:token-name path}})))))

(defn- ensure-dependency
  "Makes the value of `dep` available in the cache if possible. Returns
  `[state status]` where status is `:ok`, `:cycle`, an error map or
  `:missing`."
  [st dep]
  (cond
    (contains? (:visiting st) dep)
    [st :cycle]

    (or (token? st dep) (contains? (:sub-fields st) dep))
    (let [st (resolve-name st dep)
          v (get-in st [:resolved dep])]
      [st (cond
            ;; still unwinding a cycle: the current token is on it too
            (::cycle-root st) :cycle
            (err/error-map? v) v
            :else :ok)])

    (contains? (:resolved st) dep)
    (let [v (get-in st [:resolved dep])]
      [st (if (err/error-map? v) v :ok)])

    (and (contains? (:prefixes st) dep) (not (token? st dep)))
    [(resolve-prefix st dep) :ok]

    :else
    (if-let [parent (parent-token st dep)]
      (let [st (resolve-virtual-child st dep parent)
            v (get-in st [:resolved dep])]
        [st (if (err/error-map? v) v :ok)])
      [(update st :missing conj dep) :missing])))

(defn- constants-table
  "Initial variable table for one evaluation. Mutable constants are copied
  so a script cannot alter them for the next token; immutable ones are
  shared."
  [{:keys [constants mutable-constants?]}]
  (if mutable-constants?
    (into {} (map (fn [[k v]] [k (sym/clone-if-mutable v)])) constants)
    constants))

(defn- interpret-ast
  "Evaluates a parsed token against the current cache. Returns a symbol
  or an error map."
  [st ast]
  (try
    (interp/interpret ast {:references (:cache st)
                           :config (:config st)
                           :table (constants-table st)})
    (catch #?(:clj Throwable :cljs :default) e
      (exception->error e))))

(defn- store-result
  "Records the evaluation result of a token or sub-field."
  [st name result]
  (if (err/error-map? result)
    (-> st
        (assoc-in [:resolved name] result)
        (add-error name result))
    (let [result (if (nil? result) "" result)]
      (-> st
          (assoc-in [:resolved name] result)
          (assoc-in [:cache name] result)
          (lint name result)))))

(defn- circular-error
  [name]
  (err/error-map :processor/circular-dependency {:data {:tokens name}}))

(defn- resolve-parsed
  [st name {:keys [ast deps]}]
  (loop [st st deps deps]
    (if-let [dep (first deps)]
      (let [[st status] (ensure-dependency st dep)]
        (case status
          :ok (recur st (rest deps))
          :cycle (-> st
                     (assoc-in [:resolved name] (circular-error name))
                     (add-error name (circular-error name))
                     (cond-> (not (::cycle-root st)) (assoc ::cycle-root dep)))
          :missing (let [error (dependency-error name dep (err/error-map :processor/token-not-found {:data {:token-name dep}}))]
                     (-> st (assoc-in [:resolved name] error) (add-error name error)))
          (let [error (dependency-error name dep status)]
            (-> st (assoc-in [:resolved name] error) (add-error name error)))))
      (store-result st name (interpret-ast st ast)))))

(defn- resolve-structured
  [st name {:keys [fields]}]
  (let [st (reduce (fn [st [path]] (resolve-name st path)) st fields)
        failure (some (fn [[path]]
                        (let [v (get-in st [:resolved path])]
                          (when (err/error-map? v) v)))
                      fields)]
    (if failure
      (-> st
          (assoc-in [:resolved name] failure)
          (add-issue name failure))
      (let [{:keys [value type metadata]} (get-in st [:tokens name])
            resolved-fields (into {} (map (fn [[path]] [path (get-in st [:resolved path])])) fields)
            token (td/structured->symbol-with-fields name resolved-fields value (or type "unknown") metadata (:parsers st))]
        (store-result st name token)))))

(defn- resolve-name
  "Resolves a token or sub-field by name, memoized in `:resolved`."
  [st name]
  (cond
    (contains? (:resolved st) name) st
    (contains? (:visiting st) name) st
    :else
    (let [st (update st :visiting conj name)
          st (cond
               (contains? (:structured st) name) (resolve-structured st name (get-in st [:structured name]))
               (contains? (:parsed st) name) (resolve-parsed st name (get-in st [:parsed name]))
               :else st)
          st (update st :visiting disj name)]
      ;; Every token on a cycle gets its own circular dependency error.
      (if-let [root (::cycle-root st)]
        (if (= root name)
          (dissoc st ::cycle-root)
          st)
        st))))

(defn- resolve-all
  [st]
  (reduce (fn [st name] (resolve-name st name)) st (sort (keys (:tokens st)))))

;; Graph ----------------------------------------------------------------------

(defn reverse-graph
  [graph]
  (reduce-kv (fn [acc node deps]
               (reduce (fn [acc dep] (update acc dep (fnil conj #{}) node)) acc deps))
             {}
             graph))

(defn dependents
  "Tokens transitively depending on `name` (including `name`), and the
  sub graph between them."
  [graph name]
  (let [reverse (reverse-graph graph)
        affected (loop [queue [name] seen #{}]
                   (if-let [current (first queue)]
                     (if (contains? seen current)
                       (recur (rest queue) seen)
                       (recur (into (vec (rest queue)) (remove seen (get reverse current)))
                              (conj seen current)))
                     seen))]
    {:tokens affected
     :subgraph (into {} (map (fn [t] [t (set (filter affected (get graph t #{})))])) affected)}))

;; Building -------------------------------------------------------------------

(defn- public-names
  "Input token names, filtering virtual sub-field paths."
  [st]
  (keys (:tokens st)))

(defn- select-names
  "`select-keys` for many keys."
  [m names]
  (persistent!
   (reduce (fn [acc name]
             (if-let [entry (find m name)]
               (assoc! acc name (val entry))
               acc))
           (transient {})
           names)))

(defn- result
  [st]
  (let [names (public-names st)
        resolved (:resolved st)
        tokens (:tokens st)
        issues (select-names (:issues st) names)]
    {:tokens (persistent!
              (reduce (fn [acc name]
                        (let [v (get resolved name)]
                          (assoc! acc name (if (err/error-map? v) (get-in tokens [name :value]) v))))
                      (transient {})
                      names))
     :resolved (select-names resolved names)
     :issues (when (seq issues) issues)
     :graph (select-names (:graph st) names)
     :state st}))

(defn- run
  [tokens cfg parsers seed]
  (-> (initial-state tokens cfg parsers)
      (merge (select-keys seed [:resolved :cache :issues]))
      (parse-all)
      (resolve-all)
      (result)))

(defn build
  "Resolves normalized token data (`{name {:value .. :type ..}}`).
  Options:

    :config         interpreter config (default `config/make-config`)
    :object-parsers parsers for atomic structured values

  Returns `{:tokens :resolved :issues :graph :state}` where `:tokens` maps
  names to resolved symbols (or the original value for failed tokens),
  `:resolved` maps names to symbols or error maps, `:issues` maps names to
  vectors of issues (nil when clean), and `:graph` is the dependency
  graph."
  ([tokens] (build tokens nil))
  ([tokens {:keys [config object-parsers]}]
   (run (td/normalize-tokens tokens)
        (or config (config/make-config))
        (or object-parsers td/default-object-parsers)
        nil)))

(defn process-tokens
  "Like `build` but accepts raw inputs: a flat map of names to values or
  token data, or a nested token tree."
  ([tokens] (process-tokens tokens nil))
  ([tokens opts]
   (build (td/->flat-tokens tokens) opts)))

;; Incremental operations -----------------------------------------------------

(defn- expanded-reverse-graph
  "Reverse dependency edges including prefix memberships and virtual
  child parents, so a change to a token dirties everything that could
  observe it."
  [st]
  (let [graph (:graph st)
        edges (for [[node deps] graph
                    dep deps
                    source (cond
                             (contains? (:tokens st) dep) [dep]
                             (contains? (:sub-fields st) dep) [dep]
                             (contains? (:prefixes st) dep) (get-in st [:prefixes dep])
                             :else (if-let [parent (parent-token st dep)] [parent] [dep]))]
                [source node])]
    (reduce (fn [acc [source node]] (update acc source (fnil conj #{}) node)) {} edges)))

(defn- dirty-tokens
  "Tokens that must be re-resolved after `changed` names change: their
  dependents, tokens under a changed prefix, and every token that had
  issues (a change may fix them)."
  [st changed]
  (let [reverse (expanded-reverse-graph st)
        seeds (into (set changed)
                    (concat (keys (:issues st))
                            (mapcat (fn [c] (for [[t _] (:tokens st) :when (str/starts-with? t (str c "."))] t)) changed)))]
    (loop [queue (vec seeds) seen #{}]
      (if-let [current (first queue)]
        (if (contains? seen current)
          (recur (subvec queue 1) seen)
          (recur (into (subvec queue 1) (remove seen (get reverse current))) (conj seen current)))
        seen))))

(defn- seed-from
  "Resolved values of the old state that are still valid for `tokens`."
  [st tokens dirty]
  (let [keep? (fn [name]
                (and (contains? tokens name)
                     (not (contains? dirty name))
                     (= (get tokens name) (get-in st [:tokens name]))
                     (contains? (:resolved st) name)
                     (not (err/error-map? (get-in st [:resolved name])))))
        names (filter keep? (keys (:tokens st)))]
    {:resolved (select-keys (:resolved st) names)
     :cache (select-keys (:cache st) names)
     :issues (select-keys (:issues st) names)}))

(defn- rebuild
  [st tokens changed]
  (let [dirty (dirty-tokens st changed)
        seed (seed-from st tokens dirty)]
    (run tokens (:config st) (:parsers st) seed)))

(defn- ensure-token
  [st name]
  (when-not (contains? (:tokens st) name)
    (err/raise :processor/token-not-found {:data {:token-name name}})))

(defn- normalize-path
  [path]
  (str/trim (or path "")))

(defn- operation-result
  [res name extra]
  (merge (dissoc res :state)
         {:resolved (get-in res [:tokens name])
          :dependants {:graph (:subgraph (dependents (:graph res) name))}
          :state (:state res)}
         extra))

(defn create-token
  "Adds a token. Returns the build result plus `:resolved` (the new value),
  `:dependants {:graph ..}` and `:created true`."
  [st {:keys [token-path token-data]}]
  (let [name (normalize-path token-path)]
    (when (contains? (:tokens st) name)
      (err/raise :processor/token-already-exists {:data {:token-name name}}))
    (let [tokens (assoc (:tokens st) name (td/normalize token-data))
          res (rebuild st tokens [name])]
      (operation-result res name {:created true}))))

(defn update-token
  "Updates and/or renames a token. Params:

    :token-path          current name
    :token-data          new data (keeps the old one when nil)
    :token-path-renamed  new name
    :update-references?  rewrite `{old}` references in dependents

  Creates the token when it does not exist and data is given. Returns the
  build result plus `:resolved`, `:dependants` and `:updated`."
  [st {:keys [token-path token-data token-path-renamed update-references?]}]
  (let [prev (normalize-path token-path)
        new-name (some-> token-path-renamed str/trim)]
    (if-not (contains? (:tokens st) prev)
      (if token-data
        (assoc (create-token st {:token-path prev :token-data token-data}) :updated false)
        (err/raise :processor/token-not-found {:data {:token-name prev}}))
      (let [rename? (and new-name (not= new-name prev))
            _ (when (and rename? (contains? (:tokens st) new-name))
                (err/raise :processor/token-already-exists {:data {:token-name new-name}}))
            affected (disj (:tokens (dependents (:graph st) prev)) prev)
            tokens (:tokens st)
            tokens (if (and rename? update-references? (seq affected))
                     (reduce (fn [tokens dependent]
                               (let [data (get tokens dependent)
                                     v (:value data)
                                     ast (get-in st [:parsed dependent :ast])]
                                 (if (and (string? v) ast)
                                   (let [v' (ast/rename-references v ast {prev new-name})]
                                     (if (= v v') tokens (assoc tokens dependent (assoc data :value v'))))
                                   tokens)))
                             tokens
                             affected)
                     tokens)
            final (if rename? new-name prev)
            tokens (-> tokens
                       (dissoc prev)
                       (assoc final (if token-data (td/normalize token-data) (get (:tokens st) prev))))
            res (rebuild st tokens (cond-> [prev] rename? (conj new-name)))]
        (operation-result res final {:updated true})))))

(defn delete-token
  "Removes a token. Returns the build result plus `:dependants` (the
  tokens that depended on it)."
  [st {:keys [token-path]}]
  (let [name (normalize-path token-path)]
    (ensure-token st name)
    (let [subgraph (-> (dependents (:graph st) name) :subgraph (dissoc name)
                       (update-vals #(disj % name)))
          res (rebuild st (dissoc (:tokens st) name) [name])]
      (merge (dissoc res :state)
             {:dependants {:graph subgraph}
              :state (:state res)}))))

(defn resolve-value
  "Evaluates an expression against the resolved tokens without changing
  the state. Returns `{:resolved value-or-nil :issues [..]}`. With
  `:validate?` and `:type`, the value is validated as that token type."
  [st {:keys [value type validate?]}]
  (if (or (nil? value) (= value ""))
    {:resolved nil :issues []}
    (let [text (str value)
          parsed (try
                   (parser/parse text)
                   (catch #?(:clj Throwable :cljs :default) e
                     (if (err/language-error? e) {:error (err/->error-map e)} (throw e))))]
      (cond
        (:error parsed) {:resolved nil :issues [(:error parsed)]}
        (nil? (:ast parsed)) {:resolved text :issues []}
        :else
        (let [result (interpret-ast st (:ast parsed))]
          (if (err/error-map? result)
            {:resolved nil :issues [result]}
            {:resolved result
             :issues (if (and validate? type)
                       (validation/type-issues (:config st) "__preview__" type result)
                       [])}))))))

;; Result helpers -------------------------------------------------------------

(defn affected-tokens
  "Token names in the `:dependants` graph of an operation result."
  [res]
  (set (keys (get-in res [:dependants :graph]))))

(defn broken-references
  "Tokens whose issues include a missing token or dependency error."
  [res]
  (into #{}
        (keep (fn [[name issues]]
                (when (some #(and (err/error-map? %)
                                  (contains? #{:processor/token-not-found :processor/dependency-error} (:code %)))
                            issues)
                  name)))
        (:issues res)))

(defn modified-dependants
  "Affected tokens whose resolved value differs from `old-tokens`."
  [old-tokens res]
  (into #{}
        (filter (fn [name] (not= (get old-tokens name) (get-in res [:tokens name]))))
        (affected-tokens res)))

(defn token-error
  "First error issue of a token, or nil."
  [res name]
  (some #(when (error-issue? %) %) (get-in res [:issues name])))

(defn issue-with-code?
  "Whether any token (or `name` when given) has an issue with `code`."
  ([res code] (some (fn [[_ issues]] (some #(= code (:code %)) issues)) (:issues res)))
  ([res name code] (some #(= code (:code %)) (get-in res [:issues name]))))

(defn tokens-with-issues
  [res]
  (set (keys (:issues res))))

(defn errors
  "Map of token name to the first error of each failed token."
  [res]
  (into {} (keep (fn [[name _]] (when-let [e (token-error res name)] [name e]))) (:issues res)))

;; Output ---------------------------------------------------------------------

(defn tokens->data
  "Converts the `:tokens` of a result to plain data (`sym/->data`)."
  ([res] (tokens->data res nil))
  ([res opts]
   (update-vals (:tokens res) #(if (sym/symbol? %) (sym/->data % opts) %))))

(defn tokens->strings
  "Converts the `:tokens` of a result to their string form."
  [res]
  (let [cfg (get-in res [:state :config])]
    (update-vals (:tokens res) #(sym/->string-result % cfg))))

(defn nest-tokens
  "Nests a flat `{\"a.b\" v}` map into `{\"a\" {\"b\" v}}`."
  [flat]
  (reduce-kv (fn [acc path v] (assoc-in acc (str/split path #"\.") v)) {} flat))

(defn flatten-dictionaries
  "Flattens dictionary values of a tokens map into `prefix.key` entries,
  like the flat output builders of the other implementations."
  [tokens]
  (letfn [(flat [acc prefix v]
            (if (sym/dictionary? v)
              (reduce-kv (fn [acc k child] (flat acc (str prefix "." k) child)) acc (sym/value v))
              (assoc acc prefix v)))]
    (reduce-kv flat {} tokens)))

