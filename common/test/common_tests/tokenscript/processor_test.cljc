;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns common-tests.tokenscript.processor-test
  (:require
   [app.common.schema.generators :as sg]
   [app.common.schema.test :as smt]
   [app.common.tokenscript :as tsc]
   [app.common.tokenscript.config :as config]
   [app.common.tokenscript.errors :as err]
   [app.common.tokenscript.processor :as proc]
   [app.common.tokenscript.symbols :as sym]
   [app.common.tokenscript.token-data :as td]
   [clojure.string :as str]
   [clojure.test :as t]
   [common-tests.tokenscript.helpers :as h]))

(defn- process
  ([tokens] (process tokens (h/core-config)))
  ([tokens cfg] (proc/build tokens {:config cfg})))

(defn- value
  "String form of a resolved token."
  [res name]
  (let [v (get-in res [:tokens name])]
    (if (sym/symbol? v) (sym/to-string v (get-in res [:state :config])) v)))

(defn- codes
  [res name]
  (mapv :code (get-in res [:issues name])))

(t/deftest simple-resolution-test
  (let [res (process {"colors.primary" "#FF0000"
                      "colors.secondary" "{colors.primary}"
                      "spacing.base" "8"
                      "spacing.small" "{spacing.base} / 2"
                      "spacing.large" "{spacing.base} * 2px"
                      "a" "10" "b" "{a} + 5" "c" "{b} * 2"})]
    (t/is (= "#FF0000" (value res "colors.secondary")))
    (t/is (= "4" (value res "spacing.small")))
    (t/is (= "16px" (value res "spacing.large")))
    (t/is (= "30" (value res "c")))
    (t/is (nil? (:issues res)))
    (t/is (= #{"b"} (get-in res [:graph "c"])))
    (t/is (= #{} (get-in res [:graph "a"])))))

(t/deftest primitive-values-test
  (let [res (process {"n" 8 "b" true "nil" nil "kw" "uppercase" "ref" "{n} + 1"})]
    (t/is (= "8" (value res "n")))
    (t/is (= "Number" (sym/type-name (get-in res [:tokens "n"]))))
    (t/is (= "true" (value res "b")))
    (t/is (sym/null? (get-in res [:tokens "nil"])))
    (t/is (= "uppercase" (value res "kw")) "uninterpreted keywords stay strings")
    (t/is (= "9" (value res "ref")))))

(t/deftest errors-test
  (let [res (process {"a" "10"
                      "bad" "{a} + invalid syntax !@#"
                      "miss" "{nope}"
                      "miss2" "{miss} + 1"})]
    (t/is (= "10" (value res "a")))
    (t/is (= "{a} + invalid syntax !@#" (value res "bad")) "failed tokens keep their original value")
    (t/is (= [:lexer/invalid-character] (codes res "bad")))
    (t/is (= [:processor/dependency-error] (codes res "miss")))
    (t/is (= ["miss" "nope"] (get-in res [:issues "miss" 0 :data :chain])))
    (t/is (= :processor/token-not-found (get-in res [:issues "miss" 0 :data :cause :code])))
    (t/is (= ["miss2" "miss" "nope"] (get-in res [:issues "miss2" 0 :data :chain])) "chains accumulate")
    (t/is (= :processor/token-not-found (get-in res [:issues "miss2" 0 :data :cause :code])) "the root cause is kept")
    (t/is (= "Token 'miss2' failed due to dependency error: miss2 → miss → nope\nRoot cause: Token 'nope' not found"
             (get-in res [:issues "miss2" 0 :message])))
    (t/is (not (contains? (:resolved res) "nope")) "missing tokens are not invented")
    (t/is (= #{"miss" "miss2"} (proc/broken-references res)))
    (t/is (err/error-map? (proc/token-error res "bad")))
    (t/is (proc/issue-with-code? res "miss" :processor/dependency-error))))

(t/deftest circular-dependencies-test
  (let [res (process {"x" "{y}" "y" "{x}" "z" "{x} + 1" "self" "{self}"})]
    (t/is (= [:processor/circular-dependency] (codes res "x")))
    (t/is (= [:processor/circular-dependency] (codes res "y")))
    (t/is (= [:processor/circular-dependency] (codes res "self")))
    (t/is (= [:processor/dependency-error] (codes res "z")) "dependents of a cycle get a dependency error")
    (t/is (= :processor/circular-dependency (get-in res [:issues "z" 0 :data :cause :code]))))
  (t/testing "cycles through groups and dictionary keys"
    (let [res (process {"a" "{g}" "g.x" "{a}" "g.y" "{other}" "other" "1"
                        "d" "{grp}" "grp.k" "{d.k}"})]
      (t/is (= [:processor/circular-dependency] (codes res "a")))
      (t/is (= "1" (value res "g.y")) "a group member off the cycle resolves")
      (t/is (nil? (get-in res [:issues "g.y"])))
      (t/is (= [:processor/circular-dependency] (codes res "d")) "a key of a token being resolved closes a cycle")
      (t/is (= :processor/circular-dependency
               (get-in res [:issues "grp.k" 0 :data :cause :code] (get-in res [:issues "grp.k" 0 :code])))))))

(t/deftest group-references-test
  (let [res (process {"color.primary" "#FF0000"
                      "color.secondary" "{color.primary}"
                      "color.nested.deep" "#000"
                      "group" "{color}"
                      "keys" "{color}.keys()"
                      "pick" "{color}.get(\"secondary\")"})]
    (t/is (= "Dictionary" (sym/type-name (get-in res [:tokens "group"]))))
    (t/is (= "primary, secondary" (value res "keys")) "only direct children are included")
    (t/is (= "#FF0000" (value res "pick")))))

(t/deftest dictionary-children-test
  (let [res (process {"color.primary" "#FF0000"
                      "dict" "{color}"
                      "virt" "{dict.primary}"
                      "missing" "{dict.nope}"})]
    (t/is (= "#FF0000" (value res "virt")) "keys of dictionary tokens are addressable")
    (t/is (= [:processor/dependency-error] (codes res "missing")))))

(t/deftest structured-tokens-test
  (let [res (process {"base" "4"
                      "shadow" {:value {:offset-x "{base} * 2" :offset-y 4 :blur "{missing}" :color "#000"}
                                :type "shadow"}
                      "shadows" {:value [{:blur "{base}px" :color "#fff"}] :type "shadow"}
                      "plain" {:value {:a 1 :b "static"} :type "custom"}
                      "ref" "{shadows[0].blur}"
                      "ref2" "{shadow.offset-x}"})]
    (t/is (= "Token.Shadow" (sym/full-type-name (get-in res [:tokens "shadows"]))))
    (t/is (= "{blur: 4px, color: #fff}" (value res "shadows")))
    (t/is (= [4.0 "#fff"] (let [d (sym/->data (get-in res [:tokens "shadows"]))]
                            [(double (get-in d [0 "blur" :value])) (get-in d [0 "color" :value])])))
    (t/is (= "4px" (value res "ref")) "fields of structured tokens are referenceable")
    (t/is (= "8" (value res "ref2")))
    (t/is (= "{a: 1, b: static}" (value res "plain")))
    (t/testing "a failing field fails the parent with the field path"
      (t/is (map? (value res "shadow")))
      (let [[field-issue parent-issue] (get-in res [:issues "shadow"])]
        (t/is (= ["blur"] (:path field-issue)))
        (t/is (= :processor/dependency-error (:code field-issue)))
        (t/is (err/error-map? field-issue))
        (t/is (str/starts-with? (:hint field-issue) "Error at blur: "))
        (t/is (= :processor/dependency-error (:code parent-issue)))))))

(t/deftest object-parsers-test
  (let [res (proc/build {"spacing" {:value {:small {:value 8 :unit "px"}} :type "spacing"}}
                        {:config (h/core-config)
                         :object-parsers [td/number-with-unit-parser]})]
    (t/is (= "{small: 8px}" (value res "spacing")))))

(t/deftest token-name-validation-test
  (let [res (process {"bad name" "1" "ok.name" "2"})]
    (t/is (= [:processor/invalid-token-name] (codes res "bad name")))
    (t/is (= "1" (value res "bad name")) "the token still resolves")
    (t/is (nil? (get-in res [:issues "ok.name"])))))

(t/deftest inline-constants-test
  (let [res (process {"a" "red" "b" "{a}" "c" "lighten({b}, 0.1)"} (h/full-config))]
    (t/is (= "#FF0000" (value res "a")) "css color names come from the bundled constants")
    (t/is (= "Color.Oklch" (sym/full-type-name (get-in res [:tokens "c"]))))
    (t/is (nil? (:issues res)))))

(t/deftest type-validation-test
  (let [cfg (config/register-schema (h/core-config)
                                    {:uri "https://example.com/api/v1/schema/opacity/0/"
                                     :schema {:name "opacity"
                                              :type "token"
                                              :validation "if (is_number({input}) == false) [ return \"NOT_A_NUMBER\"; ]; if ({input} > 1) [ return \"OUT_OF_RANGE\"; ]; return true;"}})
        res (process {"ok" {:value "0.5" :type "opacity"}
                      "big" {:value "2" :type "opacity"}
                      "str" {:value "\"a\"" :type "opacity"}} cfg)]
    (t/is (nil? (get-in res [:issues "ok"])))
    (t/is (= ["OUT_OF_RANGE"] (codes res "big")))
    (t/is (= :warning (get-in res [:issues "big" 0 :severity])))
    (t/is (= ["NOT_A_NUMBER"] (codes res "str")))
    (t/is (= "2" (value res "big")) "validation issues do not fail the token")))

(t/deftest update-token-test
  (let [res (process {"a" "10" "b" "{a} + 5" "c" "{b} * 2" "d" "1"})
        st (:state res)
        upd (proc/update-token st "a" {:value "20"})]
    (t/is (= "20" (value upd "a")))
    (t/is (= "25" (value upd "b")))
    (t/is (= "50" (value upd "c")))
    (t/is (= #{"a" "b" "c"} (proc/affected-tokens upd)))
    (t/is (= #{"a" "b" "c"} (proc/modified-dependents (:tokens res) upd)))
    (t/is (= "1" (value upd "d")) "unaffected tokens keep their value")
    (t/testing "updating an unknown token creates it"
      (t/is (= "2" (value (proc/update-token st "e" {:value "{d} + 1"}) "e"))))
    (t/testing "a value can be broken and fixed again"
      (let [broken (proc/update-token st "a" {:value "{zzz}"})
            fixed (proc/update-token (:state broken) "a" {:value "1"})]
        (t/is (= #{"a" "b" "c"} (proc/broken-references broken)))
        (t/is (nil? (:issues fixed)))
        (t/is (= "12" (value fixed "c")))))))

(t/deftest rename-token-test
  (let [res (process {"a" "10" "b" "{a} + 5"})
        rn (proc/rename-token (:state res) "a" "alpha" {:update-references? true})]
    (t/is (= "{alpha} + 5" (get-in rn [:state :tokens "b" :value])))
    (t/is (= "15" (value rn "b")))
    (t/is (= #{"alpha" "b"} (proc/affected-tokens rn)))
    (t/is (not (contains? (:tokens rn) "a"))))
  (t/testing "without reference updates the dependents break"
    (let [res (process {"a" "10" "b" "{a} + 5"})
          rn (proc/rename-token (:state res) "a" "alpha")]
      (t/is (= #{"b"} (proc/broken-references rn)))))
  (let [st (:state (process {"a" "1" "b" "2"}))]
    (t/is (= :processor/token-already-exists (h/error-code #(proc/rename-token st "a" "b"))))
    (t/is (= :processor/token-not-found (h/error-code #(proc/rename-token st "x" "y"))))))

(t/deftest create-and-delete-token-test
  (let [res (process {"a" "10" "b" "{a} + 5"})
        cr (proc/create-token (:state res) "c" {:value "{b} * 2"})]
    (t/is (= "30" (value cr "c")))
    (t/is (= :processor/token-already-exists
             (h/error-code #(proc/create-token (:state cr) "c" {:value "1"}))))
    (let [del (proc/delete-token (:state cr) "a")]
      (t/is (not (contains? (:tokens del) "a")))
      (t/is (= #{"b" "c"} (proc/broken-references del)))
      (t/is (= #{"b" "c"} (proc/affected-tokens del)))
      (t/is (= :processor/token-not-found
               (h/error-code #(proc/delete-token (:state del) "a")))))))

(defn- snapshot
  "Resolved strings and issue codes of a result, to compare results."
  [res]
  {:tokens (proc/tokens->strings res)
   :issues (update-vals (or (:issues res) {}) #(frequencies (map :code %)))})

(defn- same-as-build?
  "Whether an incremental result equals a fresh build of its sets."
  [res]
  (= (snapshot (proc/build (get-in res [:state :sets]) {:config (get-in res [:state :config])}))
     (snapshot res)))

(defn- build-sets
  [sets]
  (proc/build sets {:config (h/core-config)}))

(t/deftest sets-test
  (let [res (build-sets [[:core {"color.bg" {:value "#fff" :metadata {:id 1}}
                                 "space" "8"}]
                         [:dark {"color.bg" {:value "#000" :metadata {:id 2}}
                                 "double" "{space} * 2"}]])]
    (t/is (= "#000" (value res "color.bg")) "later sets win")
    (t/is (= "16" (value res "double")) "references reach across sets")
    (t/is (= :dark (proc/source res "color.bg")))
    (t/is (= :core (proc/source res "space")))
    (t/is (= {:id 2} (proc/metadata res "color.bg")))
    (t/is (= :processor/token-set-not-found
             (h/error-code #(proc/update-token (:state res) "x" {:value "1"} {:set :nope}))))))

(t/deftest shadowed-update-test
  (let [res (build-sets [[:core {"a" "1" "b" "{a} + 1"}]
                         [:dark {"a" "10"}]])
        shadowed (proc/update-token (:state res) "a" {:value "2"} {:set :core})]
    (t/is (= "11" (value shadowed "b")))
    (t/is (= {} (:dependents shadowed)) "an overridden definition affects nothing")
    (t/is (identical? (get-in res [:tokens "b"]) (get-in shadowed [:tokens "b"])))
    (t/testing "deleting the override uses the earlier definition"
      (let [del (proc/delete-token (:state shadowed) "a" {:set :dark})]
        (t/is (= "2" (value del "a")))
        (t/is (= "3" (value del "b")))
        (t/is (same-as-build? del))))
    (t/testing "by default operations apply to the set a token is taken from"
      (let [upd (proc/update-token (:state res) "a" {:value "20"})]
        (t/is (= "20" (get-in upd [:state :sets 1 1 "a" :value])))
        (t/is (= "1" (get-in upd [:state :sets 0 1 "a" :value])))))))

(t/deftest set-sets-test
  (let [core {"base" "8" "other" "{base} + 1" "color.bg" "{mode.bg}"}
        res (build-sets [[:core core] [:light {"mode.bg" "#fff"}]])
        dark (proc/set-sets (:state res) [[:core core] [:dark {"mode.bg" "#000"}]])]
    (t/is (= "#000" (value dark "color.bg")))
    (t/is (identical? (get-in res [:tokens "other"]) (get-in dark [:tokens "other"]))
          "tokens that cannot see the change keep their value")
    (t/is (same-as-build? dark))
    (t/testing "removing a set"
      (let [none (proc/set-sets (:state dark) [[:core core]])]
        (t/is (= [:processor/dependency-error] (codes none "color.bg")))
        (t/is (same-as-build? none))))))

(t/deftest incremental-references-test
  (let [res (process {"color.primary" "#FF0000"
                      "group" "{color}"
                      "pick" "{group.primary}"
                      "base" "4"
                      "shadow" {:value {:blur "{base} * 2"} :type "shadow"}
                      "blur" "{shadow.blur}"
                      "waiting" "{later}"
                      "bad name" "{base}"})
        st (:state res)]
    (t/testing "groups and dictionary keys follow their members"
      (let [upd (proc/update-token st "color.primary" {:value "#00FF00"})]
        (t/is (= "#00FF00" (value upd "pick")))
        (t/is (same-as-build? upd)))
      (let [cr (proc/create-token st "color.extra" {:value "#000"})]
        (t/is (= "Dictionary" (sym/type-name (get-in cr [:tokens "group"]))))
        (t/is (= "2" (sym/to-string (sym/length (get-in cr [:tokens "group"])))))
        (t/is (same-as-build? cr))))
    (t/testing "fields of structured tokens follow their references"
      (let [upd (proc/update-token st "base" {:value "5"})]
        (t/is (= "10" (value upd "blur")))
        (t/is (= [:processor/invalid-token-name] (codes upd "bad name")) "re-resolved tokens keep their name issue")
        (t/is (same-as-build? upd))))
    (t/testing "creating a missing token fixes its dependents"
      (let [cr (proc/create-token st "later" {:value "1"})]
        (t/is (= "1" (value cr "waiting")))
        (t/is (nil? (get-in cr [:issues "waiting"])))
        (t/is (same-as-build? cr))))
    (t/testing "the state an operation started from is unchanged"
      (proc/update-token st "base" {:value "100"})
      (t/is (= "8" (value res "blur"))))))

(t/deftest incremental-matches-build-test
  (let [ops [[:update "a" "{c}"] [:update "c" "1"] [:update "c" "{a}"] [:delete "b"]
             [:create "b" "{a} * 3"] [:update "a" "{missing}"] [:create "missing" "2"]
             [:update "s" "{x}"] [:rename "a" "z"] [:update "d" {:p "{z}" :q "{b}"}]]
        start (:state (process {"a" "10" "b" "{a} + 5" "c" "{b} * 2" "d" {:p "{a}" :q "1"}
                                "g.x" "{b}" "g.y" "2" "s" "{g}" "x" "{s}"}))]
    (reduce (fn [st [op name arg]]
              (let [res (case op
                          :update (proc/update-token st name {:value arg})
                          :create (proc/create-token st name {:value arg})
                          :delete (proc/delete-token st name)
                          :rename (proc/rename-token st name arg {:update-references? true}))]
                (t/is (same-as-build? res) (str "after " op " " name))
                (:state res)))
            start
            ops)))

(def ^:private prop-names
  ["a" "b" "c" "g.x" "g.y" "d" "dict" "bad name"])

(def ^:private prop-values
  (into ["1" "2" 3 nil "" "1 +" "uppercase" "{g}" "{dict.x}" "{d.p}"
         "variable q: Number = 2; q * 3" {:p "{a}" :q "2"} [{:p "{b} * 2"}]]
        (mapcat (fn [n] [(str "{" n "}") (str "{" n "} + 1")]))
        prop-names))

(def ^:private prop-set-keys
  [:core :light :dark])

(def ^:private gen-sets
  (let [tokens (sg/map-of (sg/elements prop-names) (sg/elements prop-values) {:max-elements 6})]
    (sg/mcat (fn [ks] (sg/fmap #(mapv vector ks %) (apply sg/tuple (repeat (count ks) tokens))))
             (sg/filter seq (sg/subseq prop-set-keys)))))

(def ^:private gen-op
  (let [token-name (sg/elements prop-names)
        set-key (sg/elements [nil :core :dark])]
    (sg/one-of (sg/tuple (sg/elements [:update :create]) token-name (sg/elements prop-values) set-key)
               (sg/tuple (sg/elements [:delete]) token-name set-key)
               (sg/tuple (sg/elements [:rename]) token-name token-name sg/boolean)
               (sg/tuple (sg/elements [:sets]) gen-sets))))

(defn- apply-op
  "Result of `op` on `st`, nil when the operation is refused."
  [st [op a b c]]
  (try
    (case op
      :update (proc/update-token st a {:value b} (when c {:set c}))
      :create (proc/create-token st a {:value b} (when c {:set c}))
      :delete (proc/delete-token st a (when b {:set b}))
      :rename (proc/rename-token st a b {:update-references? c})
      :sets (proc/set-sets st a))
    (catch #?(:clj Exception :cljs :default) e
      (when-not (contains? #{:processor/token-not-found :processor/token-already-exists :processor/token-set-not-found}
                           (:code (ex-data e)))
        (throw e)))))

(defn- normalize-cycle-issues
  "Result where a dependency error caused by a cycle counts as a circular
  dependency, as penpot shows both. Which tokens of a cycle get which of
  the two depends on where resolution enters the cycle, and that differs
  between a build and an update."
  [res]
  (update res :issues
          (fn [issues]
            (when issues
              (update-vals issues
                           (fn [is]
                             (mapv #(if (and (= :processor/dependency-error (:code %))
                                             (= :processor/circular-dependency (get-in % [:data :cause :code])))
                                      (assoc % :code :processor/circular-dependency)
                                      %)
                                   is)))))))

(t/deftest incremental-operations-property-test
  (smt/check!
   (smt/for [sets gen-sets
             ops (sg/vector gen-op 1 12)]
     (let [cfg (h/core-config)]
       (loop [st (:state (proc/build sets {:config cfg})) ops ops]
         (if-let [op (first ops)]
           (if-let [res (apply-op st op)]
             (let [fresh (proc/build (get-in res [:state :sets]) {:config cfg})]
               (if (= (snapshot (normalize-cycle-issues fresh))
                      (snapshot (normalize-cycle-issues res)))
                 (recur (:state res) (rest ops))
                 false))
             (recur st (rest ops)))
           true))))
   :num 100))

(t/deftest resolve-value-test
  (let [st (:state (process {"a" "10" "b" "{a} + 5"}))]
    (t/is (= "16px" (sym/to-string (:value (proc/resolve-value st "{b} + 1px")))))
    (t/is (= [:interpreter/unknown-reference] (mapv :code (:issues (proc/resolve-value st "{zzz}")))))
    (t/is (= [:lexer/invalid-character] (mapv :code (:issues (proc/resolve-value st "1 ´ 2")))))
    (t/is (= {:value nil :issues []} (proc/resolve-value st "")))))

(t/deftest dependents-test
  (let [graph {"a" #{} "b" #{"a"} "c" #{"b"} "d" #{"a"} "e" #{}}
        {:keys [tokens subgraph]} (proc/dependents graph "a")]
    (t/is (= #{"a" "b" "c" "d"} tokens))
    (t/is (= {"a" #{} "b" #{"a"} "c" #{"b"} "d" #{"a"}} subgraph))))

(t/deftest outputs-test
  (let [res (process {"a" "8px" "c" "#fff" "l" "1, 2"})]
    (t/is (= {"a" "8px" "c" "#fff" "l" "1, 2"} (proc/tokens->strings res)))
    (t/is (= {"a" {:value 8.0 :unit "px"} "c" {:type "Hex" :value "#fff" :alpha nil} "l" [1.0 2.0]}
             (-> (proc/tokens->data res)
                 (update-in ["a" :value] double)
                 (update "l" #(mapv double %)))))))

(t/deftest facade-test
  (let [res (tsc/build {"a" "lighten(red, 0.1)"})]
    (t/is (= "Color.Oklch" (sym/full-type-name (get-in res [:tokens "a"]))))
    (t/is (= "Color.Hex" (sym/full-type-name (tsc/color->hex (get-in res [:tokens "a"])))))))

(t/deftest long-reference-chains-test
  ;; Resolution recurses per reference: node overflows its stack at about
  ;; 20000 links, far beyond real token files.
  (let [n 1000
        tokens (into {"t0" "1"} (map (fn [i] [(str "t" i) (str "{t" (dec i) "} + 1")])) (range 1 n))]
    (t/is (= (str n) (value (process tokens) (str "t" (dec n)))))))
