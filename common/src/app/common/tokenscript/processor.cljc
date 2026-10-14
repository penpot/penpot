;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.common.tokenscript.processor
  "Resolves design tokens whose values reference each other, from one
  token map or an ordered vector of token sets where later sets win.

    (build {\"a\" {:value \"8\"} \"b\" {:value \"{a} * 2\"}} {:config config})
    ;; => {:tokens {\"a\" #Number 8 \"b\" #Number 16} :issues nil :graph {..} :state ..}

    (build [[core-id core-tokens] [dark-id dark-tokens]] {:config config})

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
  `update-token`, `rename-token`, `delete-token`, `set-sets`,
  `resolve-value`) that only re-parse the changed tokens and only
  re-resolve the tokens that can observe the change. They return new
  states and leave the one they started from unchanged.

  See docs/technical-guide/developer/subsystems/tokenscript/resolver.md."
  (:require
   [app.common.tokenscript.ast :as ast]
   [app.common.tokenscript.config :as config]
   [app.common.tokenscript.errors :as err]
   [app.common.tokenscript.interpreter :as interp]
   [app.common.tokenscript.lexer :as lex]
   [app.common.tokenscript.parser :as parser]
   [app.common.tokenscript.symbols :as sym]
   [app.common.tokenscript.token-data :as td]
   [app.common.tokenscript.util :as u]
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
               [(u/name-key name)
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
;; unwinds a cycle. `reverse` is a delay until the first incremental
;; operation needs it, see `reverse-index`.
(defrecord State [config
                  parsers
                  sets               ; [[set-key {name token-data}]], later sets win
                  tokens             ; name -> token data of the winning set
                  constants          ; `u/name-key` -> symbol
                  mutable-constants? ; constants are copied per evaluation, see `constants-table`
                  parsed             ; name -> {:ast :deps}
                  sub-fields         ; field path -> {:parent :field-path}
                  structured         ; name -> {:fields [[path expr]]}
                  prefixes           ; prefix -> #{member token}
                  direct-children    ; prefix -> #{short name}
                  graph              ; name -> #{dep}
                  reverse            ; dep -> #{name}, the inverse of `graph`
                  resolved           ; name -> symbol | error map
                  cache              ; reference path -> value
                  derived            ; #{group or dictionary key path in `cache`/`resolved`}
                  issues             ; name -> [issue]
                  missing            ; #{reference to no token}
                  visiting           ; #{name being resolved}
                  cycle-root])

(defn- initial-state
  [sets tokens cfg parsers]
  (let [constants (constant-symbols cfg)]
    (map->State
     {:config cfg
      :parsers parsers
      :sets sets
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
      :derived #{}
      :issues {}
      :missing #{}
      :visiting #{}})))

;; Two level update of the state, cheaper than `assoc-in` on the hot paths
(defn- put
  [st k name v]
  (assoc st k (assoc (get st k) name v)))

(defn- put-derived
  "`put` for a group or dictionary key entry, which no token owns."
  [st k name v]
  (-> (put st k name v)
      (update :derived conj name)))

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

(defn- resolve-until-cycle
  "Resolves the names of `items` (`name-of` each) in order, stopping when
  one of them closes a cycle through a token being resolved: the names
  after it would be resolved while the cycle unwinds and be taken for
  part of it."
  [st name-of items]
  (reduce (fn [st item]
            (let [st (resolve-name st (name-of item))]
              (if (:cycle-root st) (reduced st) st)))
          st
          items))

(defn- resolve-prefix
  "Resolves the expression tokens directly below `prefix`, the entries of
  the group, and caches them as a dictionary. Tokens further down are
  not part of the value and are left alone."
  [st prefix]
  (let [shorts (sort (get (:direct-children st) prefix))
        st (resolve-until-cycle st #(str prefix "." %) shorts)
        entries (into (sym/value (sym/dictionary nil))
                      (keep (fn [short]
                              (let [path (str prefix "." short)]
                                (when (contains? (:cache st) path)
                                  (let [v (get (:cache st) path)]
                                    [short (sym/clone-if-mutable (sym/data->symbol v))])))))
                      shorts)]
    (if (and (seq entries) (not (:cycle-root st)))
      (put-derived st :cache prefix (sym/dictionary entries))
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
      (put-derived st :resolved path (dependency-error path parent parent-value))

      ;; Only direct keys of a dictionary token are reachable, like the
      ;; one level flattening of the other implementations.
      (sym/dictionary? parent-value)
      (let [key (subs path (inc (count parent)))
            v (when-not (str/includes? key ".") (get (sym/value parent-value) key))]
        (if (some? v)
          (put-derived st :cache path (sym/clone-if-mutable v))
          (put-derived st :resolved path (err/error-map :processor/token-not-found {:data {:token-name path}}))))

      :else
      (put-derived st :resolved path (err/error-map :processor/token-not-found {:data {:token-name path}})))))

(defn- dependency-status
  "`[state status]` for a resolved `dep`."
  [st dep]
  (let [v (get (:resolved st) dep)]
    (cond
      ;; still unwinding a cycle: the current token is on it too
      (:cycle-root st) [st :cycle]
      (err/error-map? v) [st v]
      :else [st :ok])))

(defn- ensure-dependency
  "Makes the value of `dep` available in the cache if possible. Returns
  `[state status cycle-root]` where status is `:ok`, `:cycle`, an error
  map or `:missing`, and `cycle-root` is the token being resolved that a
  new cycle leads back to. A group or a key of a token being resolved
  closes a cycle too."
  [st dep]
  (cond
    (contains? (:visiting st) dep)
    [st :cycle dep]

    (or (token? st dep) (contains? (:sub-fields st) dep))
    (dependency-status (resolve-name st dep) dep)

    (contains? (:resolved st) dep)
    (dependency-status st dep)

    :else
    (let [st (ensure-prefix-index st)
          visiting (:visiting st)]
      (if (contains? (:prefixes st) dep)
        (if-let [member (some #(visiting (str dep "." %)) (get (:direct-children st) dep))]
          [st :cycle member]
          (dependency-status (resolve-prefix st dep) dep))
        (if-let [parent (parent-token st dep)]
          (if (contains? visiting parent)
            [st :cycle parent]
            (dependency-status (resolve-virtual-child st dep parent) dep))
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
      (let [[st status cycle-root] (ensure-dependency st dep)]
        (case status
          :ok (recur st (rest deps))
          :cycle (-> st
                     (put :resolved name (circular-error name))
                     (add-error name (circular-error name))
                     (cond-> (not (:cycle-root st)) (assoc :cycle-root cycle-root)))
          :missing (let [error (dependency-error name dep (err/error-map :processor/token-not-found {:data {:token-name dep}}))]
                     (-> st (put :resolved name error) (add-error name error)))
          (let [error (dependency-error name dep status)]
            (-> st (put :resolved name error) (add-error name error)))))
      (store-result st name (interpret-ast st ast)))))

(defn- resolve-structured
  [st name {:keys [fields]}]
  (let [st (resolve-until-cycle st first fields)
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

;; Sets -----------------------------------------------------------------------
;;
;; The input is an ordered vector of sets, `[[set-key tokens] ..]`; a name
;; defined by several sets takes its data from the last of them. Set keys
;; are opaque: penpot passes the set ids.

(defn- normalize-sets
  [tokens]
  (if (map? tokens)
    [[nil (td/normalize-tokens tokens)]]
    (mapv (fn [[k set-tokens]] [k (td/normalize-tokens set-tokens)]) tokens)))

(defn- merge-sets
  [sets]
  (if (= 1 (count sets))
    (second (first sets))
    (reduce (fn [acc [_ set-tokens]] (into acc set-tokens)) {} sets)))

(defn- source-index
  "Index of the last set defining `name`, nil when none does."
  [sets name]
  (loop [i (dec (count sets))]
    (when (>= i 0)
      (if (contains? (second (nth sets i)) name)
        i
        (recur (dec i))))))

(defn- winner
  [sets name]
  (when-let [i (source-index sets name)]
    (get-in sets [i 1 name])))

;; Building -------------------------------------------------------------------

(defn- run
  [sets cfg parsers]
  (let [st (parse-all (initial-state sets (merge-sets sets) cfg parsers))
        graph (:graph st)]
    (-> st
        (assoc :reverse (delay (reverse-graph graph)))
        (resolve-all)
        (result))))

(defn build
  "Resolves token data, `{name {:value .. :type .. :metadata ..}}`, or an
  ordered vector of sets of it, `[[set-key tokens] ..]`, where a token
  defined by several sets is taken from the last one. Options:

    :config         interpreter config (default `config/make-config`)
    :object-parsers parsers for atomic structured values

  Returns `{:tokens :resolved :issues :graph :state}` where `:tokens` maps
  names to resolved symbols (or the original value for failed tokens),
  `:resolved` maps names to symbols or error maps, `:issues` maps names to
  vectors of issues (nil when clean), and `:graph` is the dependency
  graph."
  ([tokens] (build tokens nil))
  ([tokens {:keys [config object-parsers]}]
   (run (normalize-sets tokens)
        (or config (config/make-config))
        (or object-parsers td/default-object-parsers))))

;; Incremental operations -----------------------------------------------------
;;
;; An operation changes the data of some names in the sets and re-resolves
;; what can observe the change: the changed names and, through the reverse
;; graph, the tokens referencing them, a group above them (`{color}`) or a
;; key below them (`{theme.dark}`). Every other value is kept.

(defn- reverse-index
  [st]
  (let [r (:reverse st)]
    (if (delay? r) @r r)))

(defn- set-index
  [sets set-key]
  (or (first (keep-indexed (fn [i [k]] (when (= k set-key) i)) sets))
      (err/raise :processor/token-set-not-found {:data {:set-name (str set-key)}})))

(defn- last-set
  [sets]
  (if (seq sets)
    (dec (count sets))
    (err/raise :processor/token-set-not-found {:data {:set-name "(no sets)"}})))

(defn- target-set
  "Index of the set an operation on `name` applies to: the `:set` option,
  else the set `name` is taken from, else the last set."
  [sets name opts]
  (if (contains? opts :set)
    (set-index sets (:set opts))
    (or (source-index sets name) (last-set sets))))

(defn- field-names
  [st name]
  (map first (get-in st [:structured name :fields])))

(defn- disj-reverse
  [rev dep node]
  (let [nodes (disj (get rev dep) node)]
    (if (empty? nodes) (dissoc rev dep) (assoc rev dep nodes))))

(defn- remove-node
  "State without the parse and resolution data of `name` and its fields."
  [st name]
  (reduce (fn [st node]
            (-> st
                (assoc :reverse (reduce #(disj-reverse %1 %2 node) (:reverse st) (get (:graph st) node)))
                (update :graph dissoc node)
                (update :parsed dissoc node)
                (update :sub-fields dissoc node)
                (update :resolved dissoc node)
                (update :cache dissoc node)
                (update :issues dissoc node)))
          (update st :structured dissoc name)
          (cons name (field-names st name))))

(defn- add-edges
  [st name]
  (reduce (fn [st node]
            (assoc st :reverse (reduce (fn [rev dep] (update rev dep (fnil conj #{}) node))
                                       (:reverse st)
                                       (get (:graph st) node))))
          st
          (cons name (field-names st name))))

(defn- reparse
  "Parses `name` again from its current token data."
  [st name]
  (let [st (remove-node st name)]
    (if-let [data (get (:tokens st) name)]
      (-> st (parse-token name data) (add-edges name))
      st)))

(defn- keys-below
  "Reverse graph keys that are neither tokens nor fields (groups, keys of
  dictionary tokens, missing tokens) by each dot prefix."
  [st]
  (reduce (fn [acc k]
            (if (or (token? st k) (contains? (:sub-fields st) k))
              acc
              (reduce #(update %1 %2 (fnil conj []) k) acc (prefixes-of k))))
          {}
          (keys (:reverse st))))

(defn- dirty-names
  "`seeds` and every name that can observe a change of them."
  [st seeds]
  (let [rev (:reverse st)
        below (delay (keys-below st))
        observers (fn [name]
                    (concat (get rev name)
                            (mapcat #(get rev %) (prefixes-of name))
                            (mapcat #(get rev %) (get @below name))
                            (field-names st name)))]
    (loop [todo (vec seeds) seen (set seeds)]
      (if-let [name (peek todo)]
        (let [new (remove seen (observers name))]
          (recur (into (pop todo) new) (into seen new)))
        seen))))

(defn- reset-name
  "Drops the resolution of `name`, keeping its invalid name issue."
  [st name]
  (let [st (-> st
               (update :resolved dissoc name)
               (update :cache dissoc name)
               (update :issues dissoc name))]
    (if (td/valid-token-path? name)
      st
      (add-issue st name (err/error-map :processor/invalid-token-name {:data {:token-name name}})))))

(defn- drop-derived
  [{:keys [derived] :as st}]
  (assoc st
         :cache (apply dissoc (:cache st) derived)
         :resolved (apply dissoc (:resolved st) derived)
         :derived #{}))

(defn- apply-changes
  "State with the token data `tokens`, re-resolving the `changed` names
  and the names observing them."
  [st tokens changed]
  (if (empty? changed)
    (assoc st :tokens tokens)
    (let [st (-> (drop-derived st)
                 (assoc :reverse (reverse-index st)))
          parsed-before (filterv #(contains? (:parsed st) %) changed)
          old-fields (mapcat #(field-names st %) changed)
          st (reduce reparse (assoc st :tokens tokens) changed)
          dirty (dirty-names st (concat changed old-fields (mapcat #(field-names st %) changed)))
          changed? (set changed)
          ;; Structured tokens are parsed again to get fresh fields
          restructured (filter #(and (contains? (:structured st) %) (not (changed? %))) dirty)
          st (reduce reparse st restructured)
          fresh (into changed? (concat restructured (mapcat #(field-names st %) restructured)))
          st (reduce reset-name st (filter #(and (contains? (:parsed st) %) (token? st %) (not (fresh %))) dirty))
          membership-changed? (not= parsed-before (filterv #(contains? (:parsed st) %) changed))]
      (-> (cond-> st
            membership-changed? (assoc :prefixes nil :direct-children nil))
          (as-> st (reduce resolve-name st (sort dirty)))))))

(defn- change
  "`[state changed?]` for `sets` in which the data of `names` may have
  changed."
  [st sets names]
  (let [old (:tokens st)
        tokens (reduce (fn [tokens name]
                         (if-let [data (winner sets name)]
                           (assoc tokens name data)
                           (dissoc tokens name)))
                       old
                       names)
        changed (filterv #(not= (get old %) (get tokens %)) names)]
    [(apply-changes (assoc st :sets sets) tokens changed) (seq changed)]))

(defn- dependents-graph
  "Dependency graph between `name` and the tokens transitively depending
  on it."
  [st name]
  (let [rev (reverse-index st)
        graph (:graph st)
        affected (loop [todo [name] seen #{name}]
                   (if-let [current (peek todo)]
                     (let [new (remove seen (get rev current))]
                       (recur (into (pop todo) new) (into seen new)))
                     seen))]
    (into {} (map (fn [t] [t (into #{} (filter affected) (get graph t))])) affected)))

(defn- ensure-token
  [st name]
  (when-not (contains? (:tokens st) name)
    (err/raise :processor/token-not-found {:data {:token-name name}})))

(defn create-token
  "Adds the token `name` to the `:set` of `opts`, the last set by default.
  Returns the build result with `:dependents`, the dependency graph
  between `name` and the tokens depending on it."
  ([st name data] (create-token st name data nil))
  ([st name data opts]
   (let [name (str/trim name)
         sets (:sets st)
         i (if (contains? opts :set) (set-index sets (:set opts)) (last-set sets))
         _ (when (contains? (get-in sets [i 1]) name)
             (err/raise :processor/token-already-exists {:data {:token-name name}}))
         [st changed?] (change st (assoc-in sets [i 1 name] (td/normalize data)) [name])]
     (assoc (result st) :dependents (if changed? (dependents-graph st name) {})))))

(defn update-token
  "Sets the data of the token `name` in the `:set` of `opts`, by default
  the set it is taken from, adding it when missing. Returns the build
  result with `:dependents`; it is empty when a later set overrides the
  token."
  ([st name data] (update-token st name data nil))
  ([st name data opts]
   (let [name (str/trim name)
         sets (:sets st)
         i (target-set sets name opts)
         [st changed?] (change st (assoc-in sets [i 1 name] (td/normalize data)) [name])]
     (assoc (result st) :dependents (if changed? (dependents-graph st name) {})))))

(defn- rename-references
  "Sets with the references to `name` in its direct dependents renamed to
  `new-name`, in the sets they are taken from."
  [st sets name new-name]
  (reduce (fn [sets dependent]
            (let [i (source-index sets dependent)
                  {:keys [value] :as data} (when i (get-in sets [i 1 dependent]))
                  ast (get-in st [:parsed dependent :ast])]
              (if (and (string? value) ast)
                (assoc-in sets [i 1 dependent]
                          (assoc data :value (ast/rename-references value ast {name new-name})))
                sets)))
          sets
          (get (reverse-index st) name)))

(defn rename-token
  "Renames the token `name` to `new-name` in the `:set` of `opts`, by
  default the set it is taken from. The references to it break unless
  `:update-references?` rewrites them in its dependents. Returns the
  build result with `:dependents`."
  ([st name new-name] (rename-token st name new-name nil))
  ([st name new-name {:keys [update-references?] :as opts}]
   (let [name (str/trim name)
         new-name (str/trim new-name)
         _ (ensure-token st name)
         sets (:sets st)
         i (target-set sets name opts)
         data (or (get-in sets [i 1 name])
                  (err/raise :processor/token-not-found {:data {:token-name name}}))]
     (if (= name new-name)
       (assoc (result st) :dependents (dependents-graph st name))
       (do
         (when (contains? (:tokens st) new-name)
           (err/raise :processor/token-already-exists {:data {:token-name new-name}}))
         (let [sets (cond-> sets
                      update-references? (as-> sets (rename-references st sets name new-name))
                      :always (-> (update-in [i 1] dissoc name)
                                  (assoc-in [i 1 new-name] data)))
               names (into [name new-name] (when update-references? (get (reverse-index st) name)))
               [st _] (change st sets names)]
           (assoc (result st) :dependents (dependents-graph st new-name))))))))

(defn delete-token
  "Removes the token `name` from the `:set` of `opts`, by default the set
  it is taken from. Returns the build result with `:dependents`, the
  graph of the tokens that depended on it."
  ([st name] (delete-token st name nil))
  ([st name opts]
   (let [name (str/trim name)
         _ (ensure-token st name)
         sets (:sets st)
         i (target-set sets name opts)
         _ (when-not (contains? (get-in sets [i 1]) name)
             (err/raise :processor/token-not-found {:data {:token-name name}}))
         [st' changed?] (change st (update-in sets [i 1] dissoc name) [name])]
     (assoc (result st')
            :dependents (if changed?
                          (-> (dependents-graph st name)
                              (dissoc name)
                              (update-vals #(disj % name)))
                          {})))))

(defn set-sets
  "Replaces the sets, as `build` takes them, e.g. for a theme switch.
  Re-resolves only what the names whose data changed can reach. Returns
  the build result."
  [st sets]
  (let [sets (normalize-sets sets)
        old (:tokens st)
        tokens (merge-sets sets)
        changed (-> []
                    (into (remove #(identical? (get tokens %) (get old %))) (keys tokens))
                    (into (remove #(contains? tokens %)) (keys old)))
        [st _] (change st sets changed)]
    (result st)))

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

(defn source
  "Key of the set the token `name` is taken from."
  [res name]
  (let [sets (get-in res [:state :sets])]
    (some->> (source-index sets name) (nth sets) (first))))

(defn metadata
  "`:metadata` of the token data `name` is taken from."
  [res name]
  (get-in res [:state :tokens name :metadata]))

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
