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

  The returned `:state` supports incremental operations (`create-token`,
  `update-token`, `rename-token`, `delete-token`, `resolve-value`) that
  only re-resolve tokens affected by the change."
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
;;
;; The issues of a token are error maps, which make it unusable, and
;; validation warnings (`:tokenscript/issue`, see `validation/type-issues`).

(defn- dependency-error
  "Error for `token-name` caused by its dependency `dep` failing with
  `cause`. Carries the chain of token names down to the failing token
  and the error map of the root cause."
  [token-name dep cause]
  (let [[chain root-cause] (if (= :processor/dependency-error (:code cause))
                             [(into [token-name] (get-in cause [:data :chain]))
                              (get-in cause [:data :cause])]
                             [[token-name dep] cause])]
    (err/error-map :processor/dependency-error
                   {:data {:token-name token-name
                           :chain chain
                           :cause root-cause}})))

(defn- field-error
  "Error of a structured token field, reported on the parent token with
  the field `:path`."
  [error parent field-path]
  (let [path (td/parse-field-path field-path)
        at-path (if (seq path)
                  #(str "Error at " (str/join "." (map str path)) ": " %)
                  identity)]
    (-> error
        (assoc :token-name parent :path path)
        (update :hint at-path)
        (update :message at-path))))

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

;; State of one build. `prefixes` and `direct-children` stay nil until
;; `ensure-prefix-index`; `cycle-root` is set while the resolution
;; unwinds a cycle.
(defrecord State [config
                  parsers
                  tokens             ; name -> normalized token data
                  constants          ; lower case name -> symbol
                  mutable-constants? ; constants are copied per evaluation, see `constants-table`
                  parsed             ; name -> {:ast :deps}
                  sub-fields         ; field path -> {:parent :field-path}
                  structured         ; name -> {:fields [[path expr]]}
                  prefixes           ; prefix -> #{member token}
                  direct-children    ; prefix -> #{short name}
                  graph              ; name -> #{dep}
                  resolved           ; name -> symbol | error map
                  cache              ; reference path -> value
                  issues             ; name -> [issue]
                  missing            ; #{reference to no token}
                  visiting           ; #{name being resolved}
                  cycle-root])

(defn- initial-state
  [tokens cfg parsers]
  (let [constants (constant-symbols cfg)]
    (map->State
     {:config cfg
      :parsers parsers
      :tokens tokens
      :constants constants
      ;; checked once per build instead of per token
      :mutable-constants? (boolean (some mutable-symbol? (vals constants)))
      :parsed {}
      :sub-fields {}
      :structured {}
      :graph {}
      :resolved {}
      :cache {}
      :issues {}
      :missing #{}
      :visiting #{}})))

;; Two level update of the state, cheaper than `assoc-in` on the hot paths
(defn- put
  [st k name v]
  (assoc st k (assoc (get st k) name v)))

(defn- add-issue
  [st name i]
  (put st :issues name (conj (get (:issues st) name []) i)))

(defn- add-error
  "Records an error for a token or sub-field. Sub-field errors are stored
  on their parent with the field path."
  [st name error]
  (if-let [{:keys [parent field-path]} (get (:sub-fields st) name)]
    (add-issue st parent (field-error error parent field-path))
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
        (put :resolved name value)
        (put :cache name value)
        (lint name value)
        (update :graph assoc name #{}))))

(defn- fail-early
  [st name error]
  (-> st
      (put :resolved name error)
      (add-error name error)
      (update :graph assoc name #{})))

;; Parsing phase --------------------------------------------------------------

(defn- statement-syntax-error?
  "Inline parsing failures caused by statement keywords are retried in
  script mode."
  [e]
  (let [{:keys [code data]} (ex-data e)]
    (or (= :parser/unallowed-inline-syntax code)
        (and (= :parser/unexpected-token code)
             (contains? lex/script-only-statement-keywords (:token data))))))

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

(defn- ensure-prefix-index
  "State with `:prefixes` and `:direct-children`. The index is only needed
  for references to a group (`{colors}`) and for incremental updates, so
  builds whose references all name tokens never compute it."
  [st]
  (if (some? (:prefixes st)) st (index-prefixes st)))

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
                (put :parsed name parsed)
                (update :graph assoc name deps)))))

(defn- parse-structured
  [st name {:keys [value type metadata]}]
  (let [fields (td/string-fields value name (:parsers st))]
    (if (empty? fields)
      (resolve-early st name (td/structured->symbol value (or type "unknown") metadata (:parsers st)))
      (let [st (reduce (fn [st [path expr]]
                         (-> st
                             (put :sub-fields path
                                  {:parent name :field-path (let [rest (subs path (count name))]
                                                              (if (str/starts-with? rest ".") (subs rest 1) rest))})
                             (parse-expression path expr)))
                       st
                       fields)]
        (-> st
            (put :structured name {:fields fields})
            (update :graph assoc name (set (map first fields))))))))

(defn- parse-token
  [st name {:keys [value] :as data}]
  (let [st (if (td/valid-token-path? name)
             st
             (add-issue st name (err/error-map :processor/invalid-token-name {:data {:token-name name}})))]
    (cond
      (and (string? value) (contains? lex/uninterpreted-keyword-set value))
      (resolve-early st name (sym/string value))

      (number? value) (resolve-early st name (sym/number value))
      (boolean? value) (resolve-early st name (sym/boolean value))
      (nil? value) (resolve-early st name (sym/null))
      (or (map? value) (sequential? value)) (parse-structured st name data)
      :else (parse-expression st name (str value)))))

(defn- merge-maps
  [a b]
  (if (< (count a) (count b)) (into b a) (into a b)))

(defn- parse-all
  "Parses every token. Plain expression tokens, the common case, only add
  their AST and dependencies and are collected in transients; the others
  go through `parse-token`."
  [st]
  (let [[st parsed graph]
        (reduce-kv
         (fn [[st parsed graph] name {:keys [value] :as data}]
           (let [entry (when (and (string? value)
                                  (not (contains? lex/uninterpreted-keyword-set value))
                                  (td/valid-token-path? name))
                         (cached-parse value))]
             (if (and entry (nil? (:error entry)) (some? (:ast entry)))
               [st (assoc! parsed name (:parsed entry)) (assoc! graph name (:deps entry))]
               [(parse-token st name data) parsed graph])))
         [st (transient {}) (transient {})]
         (:tokens st))]
    (assoc st
           :parsed (merge-maps (:parsed st) (persistent! parsed))
           :graph (merge-maps (:graph st) (persistent! graph)))))

;; Resolution phase -----------------------------------------------------------

(declare ^:private resolve-name)

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
  (let [st (reduce resolve-name st (sort (get (:prefixes st) prefix)))
        entries (into (sym/value (sym/dictionary nil))
                      (keep (fn [short]
                              (let [path (str prefix "." short)]
                                (when (contains? (:cache st) path)
                                  (let [v (get (:cache st) path)]
                                    [short (sym/clone-if-mutable (sym/data->symbol v))])))))
                      (sort (get (:direct-children st) prefix)))]
    (if (seq entries)
      (put st :cache prefix (sym/dictionary entries))
      st)))

(defn- resolve-virtual-child
  "Resolves `{parent.key}` for a token `parent` whose value is a
  dictionary."
  [st path parent]
  (let [st (resolve-name st parent)
        parent-value (get (:resolved st) parent)]
    (cond
      (contains? (:cache st) path) st

      (err/error-map? parent-value)
      (put st :resolved path (dependency-error path parent parent-value))

      ;; Only direct keys of a dictionary token are reachable, like the
      ;; one level flattening of the other implementations.
      (sym/dictionary? parent-value)
      (let [key (subs path (inc (count parent)))
            v (when-not (str/includes? key ".") (get (sym/value parent-value) key))]
        (if (some? v)
          (put st :cache path (sym/clone-if-mutable v))
          (put st :resolved path (err/error-map :processor/token-not-found {:data {:token-name path}}))))

      :else
      (put st :resolved path (err/error-map :processor/token-not-found {:data {:token-name path}})))))

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
          v (get (:resolved st) dep)]
      [st (cond
            ;; still unwinding a cycle: the current token is on it too
            (:cycle-root st) :cycle
            (err/error-map? v) v
            :else :ok)])

    (contains? (:resolved st) dep)
    (let [v (get (:resolved st) dep)]
      [st (if (err/error-map? v) v :ok)])

    :else
    (let [st (ensure-prefix-index st)]
      (if (contains? (:prefixes st) dep)
        [(resolve-prefix st dep) :ok]
        (if-let [parent (parent-token st dep)]
          (let [st (resolve-virtual-child st dep parent)
                v (get (:resolved st) dep)]
            [st (if (err/error-map? v) v :ok)])
          [(update st :missing conj dep) :missing])))))

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
        (put :resolved name result)
        (add-error name result))
    (let [result (if (nil? result) "" result)]
      (-> st
          (put :resolved name result)
          (put :cache name result)
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
                     (put :resolved name (circular-error name))
                     (add-error name (circular-error name))
                     (cond-> (not (:cycle-root st)) (assoc :cycle-root dep)))
          :missing (let [error (dependency-error name dep (err/error-map :processor/token-not-found {:data {:token-name dep}}))]
                     (-> st (put :resolved name error) (add-error name error)))
          (let [error (dependency-error name dep status)]
            (-> st (put :resolved name error) (add-error name error)))))
      (store-result st name (interpret-ast st ast)))))

(defn- resolve-structured
  [st name {:keys [fields]}]
  (let [st (reduce (fn [st [path]] (resolve-name st path)) st fields)
        failure (some (fn [[path]]
                        (let [v (get (:resolved st) path)]
                          (when (err/error-map? v) v)))
                      fields)]
    (if failure
      (-> st
          (put :resolved name failure)
          (add-issue name failure))
      (let [{:keys [value type metadata]} (get (:tokens st) name)
            resolved-fields (into {} (map (fn [[path]] [path (get (:resolved st) path)])) fields)
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
               (contains? (:structured st) name) (resolve-structured st name (get (:structured st) name))
               (contains? (:parsed st) name) (resolve-parsed st name (get (:parsed st) name))
               :else st)
          st (update st :visiting disj name)]
      ;; Every token on a cycle gets its own circular dependency error.
      (if-let [root (:cycle-root st)]
        (if (= root name)
          (assoc st :cycle-root nil)
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
  (let [tokens (:tokens st)
        names (keys tokens)
        resolved (:resolved st)
        issues (select-names (:issues st) names)
        no-sub-fields? (empty? (:sub-fields st))
        ;; Without composite sub-fields or failed virtual children the
        ;; resolved map holds exactly the input names and is reused.
        exact? (and no-sub-fields?
                    (= (count resolved) (count tokens))
                    (every? #(contains? tokens %) (keys resolved)))]
    {:tokens (if exact?
               (reduce-kv (fn [acc name v]
                            (if (err/error-map? v)
                              (assoc acc name (get-in tokens [name :value]))
                              acc))
                          resolved
                          resolved)
               (persistent!
                (reduce (fn [acc name]
                          (let [v (get resolved name)]
                            (assoc! acc name (if (err/error-map? v) (get-in tokens [name :value]) v))))
                        (transient {})
                        names)))
     :resolved (if exact? resolved (select-names resolved names))
     :issues (when (seq issues) issues)
     :graph (if no-sub-fields? (:graph st) (select-names (:graph st) names))
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
  (let [st (ensure-prefix-index st)
        graph (:graph st)
        edges (for [[node deps] graph
                    dep deps
                    source (cond
                             (contains? (:tokens st) dep) [dep]
                             (contains? (:sub-fields st) dep) [dep]
                             (contains? (:prefixes st) dep) (get (:prefixes st) dep)
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
                     (= (get tokens name) (get (:tokens st) name))
                     (contains? (:resolved st) name)
                     (not (err/error-map? (get (:resolved st) name)))))
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

(defn- with-dependents
  "Build result `res` with `:dependents`, the dependency graph between
  `name` and the tokens depending on it."
  [res name]
  (assoc res :dependents (:subgraph (dependents (:graph res) name))))

(defn create-token
  "Adds the token `name`. Returns the build result with `:dependents`."
  [st name data]
  (let [name (str/trim name)]
    (when (contains? (:tokens st) name)
      (err/raise :processor/token-already-exists {:data {:token-name name}}))
    (-> (rebuild st (assoc (:tokens st) name (td/normalize data)) [name])
        (with-dependents name))))

(defn update-token
  "Sets the data of the token `name`, adding it when missing. Returns the
  build result with `:dependents`."
  [st name data]
  (let [name (str/trim name)]
    (-> (rebuild st (assoc (:tokens st) name (td/normalize data)) [name])
        (with-dependents name))))

(defn- rename-references
  "Tokens of `st` with the references to `name` in its dependents renamed
  to `new-name`."
  [st name new-name]
  (reduce (fn [tokens dependent]
            (let [{:keys [value] :as data} (get tokens dependent)
                  ast (get-in st [:parsed dependent :ast])]
              (if (and (string? value) ast)
                (assoc tokens dependent (assoc data :value (ast/rename-references value ast {name new-name})))
                tokens)))
          (:tokens st)
          (disj (:tokens (dependents (:graph st) name)) name)))

(defn rename-token
  "Renames the token `name` to `new-name`. The references to it break
  unless `:update-references?` rewrites them in its dependents. Returns
  the build result with `:dependents`."
  ([st name new-name] (rename-token st name new-name nil))
  ([st name new-name {:keys [update-references?]}]
   (let [name (str/trim name)
         new-name (str/trim new-name)]
     (ensure-token st name)
     (if (= name new-name)
       (with-dependents (result st) name)
       (do
         (when (contains? (:tokens st) new-name)
           (err/raise :processor/token-already-exists {:data {:token-name new-name}}))
         (let [tokens (if update-references?
                        (rename-references st name new-name)
                        (:tokens st))
               tokens (-> tokens (dissoc name) (assoc new-name (get tokens name)))]
           (-> (rebuild st tokens [name new-name])
               (with-dependents new-name))))))))

(defn delete-token
  "Removes the token `name`. Returns the build result with `:dependents`,
  the graph of the tokens that depended on it."
  [st name]
  (let [name (str/trim name)]
    (ensure-token st name)
    (assoc (rebuild st (dissoc (:tokens st) name) [name])
           :dependents (-> (:subgraph (dependents (:graph st) name))
                           (dissoc name)
                           (update-vals #(disj % name))))))

(defn resolve-value
  "Evaluates the expression `value` against the resolved tokens without
  changing the state. Returns `{:value symbol-or-nil :issues [..]}`. With
  `:type`, the value is validated as that token type."
  ([st value] (resolve-value st value nil))
  ([st value {:keys [type]}]
   (if (or (nil? value) (= value ""))
     {:value nil :issues []}
     (let [text (str value)
           parsed (try
                    (parser/parse text)
                    (catch #?(:clj Throwable :cljs :default) e
                      (if (err/language-error? e) {:error (err/->error-map e)} (throw e))))]
       (cond
         (:error parsed) {:value nil :issues [(:error parsed)]}
         (nil? (:ast parsed)) {:value text :issues []}
         :else
         (let [result (interpret-ast st (:ast parsed))]
           (if (err/error-map? result)
             {:value nil :issues [result]}
             {:value result
              :issues (if type
                        (validation/type-issues (:config st) "__preview__" type result)
                        [])})))))))

;; Result helpers -------------------------------------------------------------

(defn affected-tokens
  "Token names in the `:dependents` graph of an operation result."
  [res]
  (set (keys (:dependents res))))

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

(defn modified-dependents
  "Affected tokens whose resolved value differs from `old-tokens`."
  [old-tokens res]
  (into #{}
        (filter (fn [name] (not= (get old-tokens name) (get-in res [:tokens name]))))
        (affected-tokens res)))

(defn token-error
  "First error issue of a token, or nil."
  [res name]
  (some #(when (err/error-map? %) %) (get-in res [:issues name])))

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

