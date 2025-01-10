;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.types.tokens-lib
  (:require
   #?(:clj [app.common.fressian :as fres])
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.schema :as sm]
   [app.common.time :as dt]
   [app.common.transit :as t]
   [app.common.types.token :as cto]
   [clojure.set :as set]
   [clojure.walk :as walk]
   [cuerdas.core :as str]))

;; === Groups handling

(def schema:groupable-item
  [:map {:title "Groupable item"}
   [:name :string]])

(def valid-groupable-item?
  (sm/validator schema:groupable-item))

(defn split-path
  "Decompose a string in the form 'one.two.three' into a vector of strings, removing spaces."
  [path separator]
  (let [xf (comp (map str/trim)
                 (remove str/empty?))]
    (->> (str/split path separator)
         (into [] xf))))

(defn join-path
  "Regenerate a path as a string, from a vector."
  [path separator]
  (str/join separator path))

(defn group-item
  "Add a group to the item name, in the form group.name."
  [item group-name separator]
  (dm/assert!
   "expected groupable item"
   (valid-groupable-item? item))
  (update item :name #(str group-name separator %)))

(defn ungroup-item
  "Remove the first group from the item name."
  [item separator]
  (dm/assert!
   "expected groupable item"
   (valid-groupable-item? item))
  (update item :name #(-> %
                          (split-path separator)
                          (rest)
                          (join-path separator))))

(defn get-path
  "Get the groups part of the name as a vector. E.g. group.subgroup.name -> ['group' 'subgroup']"
  [item separator]
  (dm/assert!
   "expected groupable item"
   (valid-groupable-item? item))
  (split-path (:name item) separator))

(defn get-groups-str
  "Get the groups part of the name. E.g. group.subgroup.name -> group.subgroup"
  [item separator]
  (-> (get-path item separator)
      (butlast)
      (join-path  separator)))

(defn get-final-name
  "Get the final part of the name. E.g. group.subgroup.name -> name"
  [item separator]
  (dm/assert!
   "expected groupable item"
   (valid-groupable-item? item))
  (-> (:name item)
      (split-path separator)
      (last)))

(defn group?
  "Check if a node of the grouping tree is a group or a final item."
  [item]
  (d/ordered-map? item))

(defn get-children
  "Get all children of a group of a grouping tree. Each child is
   a tuple [name item], where item "
  [group]
  (dm/assert!
   "expected group node"
   (group? group))
  (seq group))

;; === Token

(def token-separator ".")

(defn get-token-path [path]
  (get-path path token-separator))

(defn split-token-path [path]
  (split-path path token-separator))

(defrecord Token [name type value description modified-at])

(def schema:token
  [:and
   [:map {:title "Token"}
    [:name cto/token-name-ref]
    [:type [::sm/one-of cto/token-types]]
    [:value :any]
    [:description [:maybe :string]]
    [:modified-at ::sm/inst]]
   [:fn (partial instance? Token)]])

(sm/register! ::token schema:token)

(def valid-token?
  (sm/validator schema:token))

(def check-token!
  (sm/check-fn ::token))

(defn make-token
  [& {:keys [] :as params}]
  (let [params (-> params
                   (dissoc :id) ;; we will remove this when old data structures are removed
                   (update :modified-at #(or % (dt/now))))
        token  (map->Token params)]

    (dm/assert!
     "expected valid token"
     (check-token! token))

    token))

(defn find-token-value-references
  "Returns set of token references found in `token-value`.

  Used for checking if a token has a reference in the value.
  Token references are strings delimited by curly braces.
  E.g.: {foo.bar.baz} -> foo.bar.baz"
  [token-value]
  (if (string? token-value)
    (some->> (re-seq #"\{([^}]*)\}" token-value)
             (map second)
             (into #{}))
    #{}))

(defn token-value-self-reference?
  "Check if the token is self referencing with its `token-name` in `token-value`.
  Simple 1 level check, doesn't account for circular self refernces across multiple tokens."
  [token-name token-value]
  (let [token-references (find-token-value-references token-value)
        self-reference? (get token-references token-name)]
    self-reference?))

(defn group-by-type [tokens]
  (let [tokens' (if (or (map? tokens)
                        (d/ordered-map? tokens))
                  (vals tokens)
                  tokens)]
    (group-by :type tokens')))

(defn filter-by-type [token-type tokens]
  (let [token-type? #(= token-type (:type %))]
    (cond
      (d/ordered-map? tokens) (into (d/ordered-map) (filter (comp token-type? val) tokens))
      (map? tokens) (into {} (filter (comp token-type? val) tokens))
      :else (filter token-type? tokens))))

;; === Token Set

(def set-prefix "S-")

(def set-group-prefix "G-")

(def set-separator "/")

(defn join-set-path-str [& args]
  (->> (filter some? args)
       (str/join set-separator)))

(defn join-set-path [path]
  (join-path path set-separator))

(defn split-set-str-path-prefix
  "Split set-path

  E.g.: \"S-some-set\"   -> [\"S-\" \"some-set\"]
        \"G-some-group\" -> [\"G-\" \"some-group\"]"
  [path-str]
  (some->> path-str
           (re-matches #"^([SG]-)(.*)")
           (rest)))

(defn add-set-path-prefix [set-name-str]
  (str set-prefix set-name-str))

(defn add-set-path-group-prefix [group-path-str]
  (str set-group-prefix group-path-str))

(defn set-full-path->set-prefixed-full-path
  "Returns token-set paths with prefixes to differentiate between sets and set-groups.

  Sets will be prefixed with `set-prefix` (S-).
  Set groups will be prefixed with `set-group-prefix` (G-)."
  [full-path]
  (let [set-path (mapv add-set-path-group-prefix (butlast full-path))
        set-name (add-set-path-prefix (last full-path))]
    (conj set-path set-name)))

(defn set-group-path->set-group-prefixed-path
  "Adds `set-group-prefix` (G-) to the `path` vector elements."
  [path]
  (mapv add-set-path-group-prefix path))

(defn set-group-path->set-group-prefixed-path-str
  [path]
  (-> (set-group-path->set-group-prefixed-path path)
      (join-set-path)))

(defn split-set-prefix [set-path]
  (some->> set-path
           (re-matches #"^([SG]-)(.*)")
           (rest)))

(defn add-set-prefix [set-name]
  (str set-prefix set-name))

(defn add-set-group-prefix [group-path]
  (str set-group-prefix group-path))

(defn add-token-set-paths-prefix
  "Returns token-set paths with prefixes to differentiate between sets and set-groups.

  Sets will be prefixed with `set-prefix` (S-).
  Set groups will be prefixed with `set-group-prefix` (G-)."
  [paths]
  (let [set-path (mapv add-set-group-prefix (butlast paths))
        set-name (add-set-prefix (last paths))]
    (conj set-path set-name)))

(defn split-token-set-path [token-set-path]
  (split-path token-set-path set-separator))

(defn split-token-set-name [token-set-name]
  (-> (split-token-set-path token-set-name)
      (add-token-set-paths-prefix)))

(defn get-token-set-path [token-set]
  (let [path (get-path token-set set-separator)]
    (add-token-set-paths-prefix path)))

(defn set-name->set-path-string [set-name]
  (-> (split-token-set-name set-name)
      (join-set-path)))

(defn set-path->set-name [set-path]
  (->> (split-token-set-path set-path)
       (map (fn [path-part]
              (or (-> (split-set-prefix path-part)
                      (second))
                  path-part)))
       (join-set-path)))

(defn get-token-set-final-name [path]
  (-> (split-token-set-path path)
      (last)))

(defn set-name->prefixed-full-path [name-str]
  (-> (split-token-set-path name-str)
      (set-full-path->set-prefixed-full-path)))

(defn get-token-set-prefixed-path [token-set]
  (let [path (get-path token-set set-separator)]
    (set-full-path->set-prefixed-full-path path)))

(defn get-prefixed-token-set-final-prefix [prefixed-path-str]
  (some-> (get-token-set-final-name prefixed-path-str)
          (split-set-str-path-prefix)
          (first)))

(defn set-name-string->prefixed-set-path-string [name-str]
  (-> (set-name->prefixed-full-path name-str)
      (join-set-path)))

(defn prefixed-set-path-string->set-path [path-str]
  (->> (split-token-set-path path-str)
       (map (fn [path-part]
              (or (-> (split-set-str-path-prefix path-part)
                      (second))
                  path-part)))))

(defn prefixed-set-path-string->set-name-string [path-str]
  (->> (prefixed-set-path-string->set-path path-str)
       (join-set-path)))

(defn prefixed-set-path-final-group?
  "Predicate if the given prefixed path string ends with a group."
  [prefixed-path-str]
  (= (get-prefixed-token-set-final-prefix prefixed-path-str) set-group-prefix))

(defn prefixed-set-path-final-set?
  "Predicate if the given prefixed path string ends with a set."
  [prefixed-path-str]
  (= (get-prefixed-token-set-final-prefix prefixed-path-str) set-prefix))

(defn replace-last-path-name
  "Replaces the last element in a `path` vector with `name`."
  [path name]
  (-> (into [] (drop-last path))
      (conj name)))

(defn tokens-tree
  "Convert tokens into a nested tree with their `:name` as the path.
  Optionally use `update-token-fn` option to transform the token."
  [tokens & {:keys [update-token-fn]
             :or {update-token-fn identity}}]
  (reduce
   (fn [acc [_ token]]
     (let [path (split-token-path (:name token))]
       (assoc-in acc path (update-token-fn token))))
   {} tokens))

(defn backtrace-tokens-tree
  "Convert tokens into a nested tree with their `:name` as the path.
  Generates a uuid per token to backtrace a token from an external source (StyleDictionary).
  The backtrace can't be the name as the name might not exist when the user is creating a token."
  [tokens]
  (reduce
   (fn [acc [_ token]]
     (let [temp-id (random-uuid)
           token (assoc token :temp/id temp-id)
           path (split-token-path (:name token))]
       (-> acc
           (assoc-in (concat [:tokens-tree] path) token)
           (assoc-in [:ids temp-id] token))))
   {:tokens-tree {} :ids {}} tokens))

(defprotocol ITokenSet
  (update-name [_ set-name] "change a token set name while keeping the path")
  (add-token [_ token] "add a token at the end of the list")
  (update-token [_ token-name f] "update a token in the list")
  (delete-token [_ token-name] "delete a token from the list")
  (get-token [_ token-name] "return token by token-name")
  (get-tokens [_] "return an ordered sequence of all tokens in the set")
  (get-set-prefixed-path-string [_] "convert set name to prefixed full path string")
  (get-tokens-tree [_] "returns a tree of tokens split & nested by their name path")
  (get-dtcg-tokens-tree [_] "returns tokens tree formated to the dtcg spec"))

(defrecord TokenSet [name description modified-at tokens]
  ITokenSet
  (update-name [_ set-name]
    (TokenSet. (-> (split-token-set-path name)
                   (drop-last)
                   (concat [set-name])
                   (join-set-path))
               description
               (dt/now)
               tokens))

  (add-token [_ token]
    (dm/assert! "expected valid token" (check-token! token))
    (TokenSet. name
               description
               (dt/now)
               (assoc tokens (:name token) token)))

  (update-token [this token-name f]
    (if-let [token (get tokens token-name)]
      (let [token' (-> (make-token (f token))
                       (assoc :modified-at (dt/now)))]
        (check-token! token')
        (TokenSet. name
                   description
                   (dt/now)
                   (if (= (:name token) (:name token'))
                     (assoc tokens (:name token') token')
                     (-> tokens
                         (d/oassoc-before (:name token) (:name token') token')
                         (dissoc (:name token))))))
      this))

  (delete-token [_ token-name]
    (TokenSet. name
               description
               (dt/now)
               (dissoc tokens token-name)))

  (get-token [_ token-name]
    (get tokens token-name))

  (get-tokens [_]
    (vals tokens))

  (get-set-prefixed-path-string [_]
    (set-name-string->prefixed-set-path-string name))

  (get-tokens-tree [_]
    (tokens-tree tokens))

  (get-dtcg-tokens-tree [_]
    (tokens-tree tokens :update-token-fn (fn [token]
                                           (cond-> {"$value" (:value token)
                                                    "$type" (cto/token-type->dtcg-token-type (:type token))}
                                             (:description token) (assoc "$description" (:description token)))))))

(def schema:token-set
  [:and [:map {:title "TokenSet"}
         [:name :string]
         [:description [:maybe :string]]
         [:modified-at ::sm/inst]
         [:tokens [:and [:map-of {:gen/max 5} :string ::token]
                   [:fn d/ordered-map?]]]]
   [:fn (partial instance? TokenSet)]])

(sm/register! ::token-set schema:token-set)

(def valid-token-set?
  (sm/validator schema:token-set))

(def check-token-set!
  (sm/check-fn ::token-set))

(defn make-token-set
  [& {:keys [] :as params}]
  (let [params    (-> params
                      (dissoc :id)
                      (update :modified-at #(or % (dt/now)))
                      (update :tokens #(into (d/ordered-map) %)))
        token-set (map->TokenSet params)]

    (dm/assert!
     "expected valid token set"
     (check-token-set! token-set))

    token-set))

;; === TokenSets (collection)

(defprotocol ITokenSets
  "Collection of sets and set groups.

  Naming conventions:
    Set name:                the complete name as a string, without prefix \"some-group/some-subgroup/some-set\".
    Set final name or fname: the last part of the name \"some-set\".
    Set path:                the groups part of the name, as a vector [\"some-group\" \"some-subgroup\"].
    Set path str:            the set path as a string \"some-group/some-subgroup\".
    Set full path:           the path including the fname, as a vector [\"some-group\", \"some-subgroup\", \"some-set\"].
    Set full path str:       the set full path as a string \"some-group/some-subgroup/some-set\".

    Set prefix:                        the two-characters prefix added to a full path item \"G-\" / \"S-\".
    Prefixed set path or ppath:        a path wit added prefixes [\"G-some-group\", \"G-some-subgroup\"].
    Prefixed set full path or pfpath:  a full path wit prefixes [\"G-some-group\", \"G-some-subgroup\", \"S-some-set\"].
    Prefixed set final name or pfname: a final name with prefix \"S-some-set\"."
  (add-set [_ token-set] "add a set to the library, at the end")
  (add-sets [_ token-set] "add a collection of sets to the library, at the end")
  (update-set [_ set-name f] "modify a set in the library")
  (delete-set-path [_ set-path] "delete a set in the library")
  (move-set [_ from-path to-path before-path before-group?] "Move token set at `from-path` to `to-path` and order it before `before-path` with `before-group?`.")
  (move-set-group [_ from-path to-path before-path before-group?] "Move token set group at `from-path` to `to-path` and order it before `before-path` with `before-group?`.")
  (set-count [_] "get the total number if sets in the library")
  (get-set-tree [_] "get a nested tree of all sets in the library")
  (get-in-set-tree [_ path] "get `path` in nested tree of all sets in the library")
  (get-sets [_] "get an ordered sequence of all sets in the library")
  (get-path-sets [_ path] "get an ordered sequence of sets at `path` in the library")
  (get-sets-at-prefix-path [_ prefixed-path-str] "get an ordered sequence of sets at `prefixed-path-str` in the library")
  (get-sets-at-path [_ path-str] "get an ordered sequence of sets at `path` in the library")
  (rename-set-group [_ from-path-str to-path-str] "renames set groups and all child set names from `from-path-str` to `to-path-str`")
  (get-ordered-set-names [_] "get an ordered sequence of all sets names in the library")
  (get-set [_ set-name] "get one set looking for name")
  (get-neighbor-set-name [_ set-name index-offset] "get neighboring set name offset by `index-offset`"))

(def schema:token-set-node
  [:schema {:registry {::node [:or ::token-set
                               [:and
                                [:map-of {:gen/max 5} :string [:ref ::node]]
                                [:fn d/ordered-map?]]]}}
   [:ref ::node]])

(sm/register! ::token-set-node schema:token-set-node)

(def schema:token-sets
  [:and
   [:map-of {:title "TokenSets"}
    :string ::token-set-node]
   [:fn d/ordered-map?]])

(sm/register! ::token-sets schema:token-sets)

(def valid-token-sets?
  (sm/validator schema:token-sets))

(def check-token-sets!
  (sm/check-fn ::token-sets))

;; === TokenTheme

(def theme-separator "/")

(defn token-theme-path [group name]
  (join-path [group name] theme-separator))

(defn split-token-theme-path [path]
  (split-path path theme-separator))

(def hidden-token-theme-group
  "")

(def hidden-token-theme-name
  "__PENPOT__HIDDEN__TOKEN__THEME__")

(def hidden-token-theme-path
  (token-theme-path hidden-token-theme-group hidden-token-theme-name))

(defprotocol ITokenTheme
  (set-sets [_ set-names] "set the active token sets")
  (enable-set [_ set-name] "enable set in theme")
  (enable-sets [_ set-names] "enable sets in theme")
  (disable-set [_ set-name] "disable set in theme")
  (disable-sets [_ set-names] "disable sets in theme")
  (toggle-set [_ set-name] "toggle a set enabled / disabled in the theme")
  (update-set-name [_ prev-set-name set-name] "update set-name from `prev-set-name` to `set-name` when it exists")
  (theme-path [_] "get `token-theme-path` from theme")
  (theme-matches-group-name [_ group name] "if a theme matches the given group & name")
  (hidden-temporary-theme? [_] "if a theme is the (from the user ui) hidden temporary theme"))

(defrecord TokenTheme [name group description is-source modified-at sets]
  ITokenTheme
  (set-sets [_ set-names]
    (TokenTheme. name
                 group
                 description
                 is-source
                 (dt/now)
                 set-names))

  (enable-set [this set-name]
    (set-sets this (conj sets set-name)))

  (enable-sets [this set-names]
    (set-sets this (set/union sets set-names)))

  (disable-set [this set-name]
    (set-sets this (disj sets set-name)))

  (disable-sets [this set-names]
    (set-sets this (or (set/difference sets set-names) #{})))

  (toggle-set [this set-name]
    (if (sets set-name)
      (disable-set this set-name)
      (enable-set this set-name)))

  (update-set-name [this prev-set-name set-name]
    (if (get sets prev-set-name)
      (TokenTheme. name
                   group
                   description
                   is-source
                   (dt/now)
                   (conj (disj sets prev-set-name) set-name))
      this))

  (theme-path [_]
    (token-theme-path group name))

  (theme-matches-group-name [this group name]
    (and (= (:group this) group)
         (= (:name this) name)))

  (hidden-temporary-theme? [this]
    (theme-matches-group-name this hidden-token-theme-group hidden-token-theme-name)))

(def schema:token-theme
  [:and [:map {:title "TokenTheme"}
         [:name :string]
         [:group :string]
         [:description [:maybe :string]]
         [:is-source [:maybe :boolean]]
         [:modified-at ::sm/inst]
         [:sets [:set {:gen/max 5} :string]]]
   [:fn (partial instance? TokenTheme)]])

(sm/register! ::token-theme schema:token-theme)

(def valid-token-theme?
  (sm/validator schema:token-theme))

(def check-token-theme!
  (sm/check-fn ::token-theme))

(def top-level-theme-group-name
  "Top level theme groups have an empty string as the theme group."
  "")

(defn top-level-theme-group? [group]
  (= group top-level-theme-group-name))

(defn make-token-theme
  [& {:keys [] :as params}]
  (let [params    (-> params
                      (dissoc :id)
                      (update :group #(or % top-level-theme-group-name))
                      (update :is-source #(or % false))
                      (update :modified-at #(or % (dt/now)))
                      (update :sets #(into #{} %)))
        token-theme (map->TokenTheme params)]

    (dm/assert!
     "expected valid token theme"
     (check-token-theme! token-theme))

    token-theme))

(defn make-hidden-token-theme
  [& {:keys [] :as params}]
  (make-token-theme (assoc params
                           :group hidden-token-theme-group
                           :name hidden-token-theme-name)))

;; === TokenThemes (collection)

(defprotocol ITokenThemes
  (add-theme [_ token-theme] "add a theme to the library, at the end")
  (update-theme [_ group name f] "modify a theme in the ilbrary")
  (delete-theme [_ group name] "delete a theme in the library")
  (theme-count [_] "get the total number if themes in the library")
  (get-theme-tree [_] "get a nested tree of all themes in the library")
  (get-themes [_] "get an ordered sequence of all themes in the library")
  (get-theme [_ group name] "get one theme looking for name")
  (get-hidden-theme [_] "get the theme hidden from the user ,
used for managing active sets without a user created theme.")
  (get-theme-groups [_] "get a sequence of group names by order")
  (get-active-theme-paths [_] "get the active theme paths")
  (get-active-themes [_] "get an ordered sequence of active themes in the library")
  (set-active-themes  [_ active-themes] "set active themes in library")
  (theme-active? [_ group name] "predicate if token theme is active")
  (activate-theme [_ group name] "adds theme from the active-themes")
  (deactivate-theme [_ group name] "removes theme from the active-themes")
  (toggle-theme-active? [_ group name] "toggles theme in the active-themes"))

(def schema:token-themes
  [:and
   [:map-of {:title "TokenThemes"}
    :string [:and [:map-of :string ::token-theme]
             [:fn d/ordered-map?]]]
   [:fn d/ordered-map?]])

(sm/register! ::token-themes schema:token-themes)

(def valid-token-themes?
  (sm/validator schema:token-themes))

(def check-token-themes!
  (sm/check-fn ::token-themes))

(def schema:active-token-themes
  [:set string?])

(def valid-active-token-themes?
  (sm/validator schema:active-token-themes))

;; === Import / Export from DTCG format

(defn walk-sets-tree-seq
  [nodes & {:keys [walk-children?]
            :or {walk-children? (constantly true)}}]
  (let [walk (fn walk [node {:keys [parent depth]
                             :or {parent []
                                  depth 0}
                             :as opts}]
               (lazy-seq
                (if (d/ordered-map? node)
                  (mapcat #(walk % opts) node)
                  (let [[k v] node]
                    (cond
                      ;;; Set
                      (and v (instance? TokenSet v))
                      [{:group? false
                        :path (split-token-set-path (:name v))
                        :parent-path parent
                        :depth depth
                        :set v}]

                      ;; Set group
                      (and v (d/ordered-map? v))
                      (let [unprefixed-path (last (split-set-str-path-prefix k))
                            path (conj parent unprefixed-path)
                            item {:group? true
                                  :path path
                                  :parent-path parent
                                  :depth depth}]
                        (if (walk-children? path)
                          [item]
                          (cons
                           item
                           (mapcat #(walk % (assoc opts :parent path :depth (inc depth))) v)))))))))]
    (walk nodes nil)))

(defn flatten-nested-tokens-json
  "Recursively flatten the dtcg token structure, joining keys with '.'."
  [tokens token-path]
  (reduce-kv
   (fn [acc k v]
     (let [child-path (if (empty? token-path)
                        (name k)
                        (str token-path "." k))]
       (if (and (map? v)
                (not (contains? v "$type")))
         (merge acc (flatten-nested-tokens-json v child-path))
         (let [token-type (cto/dtcg-token-type->token-type (get v "$type"))]
           (if token-type
             (assoc acc child-path (make-token
                                    :name child-path
                                    :type token-type
                                    :value (get v "$value")
                                    :description (get v "$description")))
             ;; Discard unknown tokens
             acc)))))
   {}
   tokens))

;; === Tokens Lib

(declare make-tokens-lib)

(defprotocol ITokensLib
  "A library of tokens, sets and themes."
  (set-path-exists? [_ path] "if a set at `path` exists")
  (set-group-path-exists? [_ path] "if a set group at `path` exists")
  (add-token-in-set [_ set-name token] "add token to a set")
  (update-token-in-set [_ set-name token-name f] "update a token in a set")
  (delete-token-from-set [_ set-name token-name] "delete a token from a set")
  (toggle-set-in-theme [_ group-name theme-name set-name] "toggle a set used / not used in a theme")
  (get-active-themes-set-names [_] "set of set names that are active in the the active themes")
  (sets-at-path-all-active? [_ group-path] "compute active state for child sets at `group-path`.
Will return a value that matches this schema:
`:none`    None of the nested sets are active
`:all`     All of the nested sets are active
`:partial` Mixed active state of nested sets")
  (get-active-themes-set-tokens [_] "set of set names that are active in the the active themes")
  (encode-dtcg [_] "Encodes library to a dtcg compatible json string")
  (decode-dtcg-json [_ parsed-json] "Decodes parsed json containing tokens and converts to library")
  (get-all-tokens [_] "all tokens in the lib")
  (validate [_]))

(deftype TokensLib [sets themes active-themes]
  ;; NOTE: This is only for debug purposes, pending to properly
  ;; implement the toString and alternative printing.
  #?@(:clj  [clojure.lang.IDeref
             (deref [_] {:sets sets
                         :themes themes
                         :active-themes active-themes})]
      :cljs [cljs.core/IDeref
             (-deref [_] {:sets sets
                          :themes themes
                          :active-themes active-themes})])

  #?@(:cljs [cljs.core/IEncodeJS
             (-clj->js [_] (js-obj "sets" (clj->js sets)
                                   "themes" (clj->js themes)
                                   "active-themes" (clj->js active-themes)))])

  ITokenSets
  (add-set [_ token-set]
    (dm/assert! "expected valid token set" (check-token-set! token-set))
    (let [path (get-token-set-prefixed-path token-set)]
      (TokensLib. (d/oassoc-in sets path token-set)
                  themes
                  active-themes)))

  (add-sets [this token-sets]
    (reduce
     (fn [lib set]
       (add-set lib set))
     this token-sets))

  (update-set [this set-name f]
    (let [prefixed-full-path (set-name->prefixed-full-path set-name)
          set (get-in sets prefixed-full-path)]
      (if set
        (let [set' (-> (make-token-set (f set))
                       (assoc :modified-at (dt/now)))
              prefixed-full-path' (get-token-set-prefixed-path set')
              name-changed? (not= (:name set) (:name set'))]
          (check-token-set! set')
          (if name-changed?
            (TokensLib. (-> sets
                            (d/oassoc-in-before prefixed-full-path prefixed-full-path' set')
                            (d/dissoc-in prefixed-full-path))
                        (walk/postwalk
                         (fn [form]
                           (if (instance? TokenTheme form)
                             (update-set-name form (:name set) (:name set'))
                             form))
                         themes)
                        active-themes)
            (TokensLib. (d/oassoc-in sets prefixed-full-path set')
                        themes
                        active-themes)))
        this)))

  (delete-set-path [_ prefixed-set-name]
    (let [prefixed-set-path (split-token-set-path prefixed-set-name)
          set-node (get-in sets prefixed-set-path)
          set-group? (not (instance? TokenSet set-node))
          set-name-string (prefixed-set-path-string->set-name-string prefixed-set-name)]
      (TokensLib. (d/dissoc-in sets prefixed-set-path)
                  ;; TODO: When deleting a set-group, also deactivate the child sets
                  (if set-group?
                    themes
                    (walk/postwalk
                     (fn [form]
                       (if (instance? TokenTheme form)
                         (disable-set form set-name-string)
                         form))
                     themes))
                  active-themes)))

  (move-set [_ from-path to-path before-path before-group?]
    (let [prefixed-from-path (set-full-path->set-prefixed-full-path from-path)
          prev-set (get-in sets prefixed-from-path)]
      (if (instance? TokenSet prev-set)
        (let [prefixed-to-path (set-full-path->set-prefixed-full-path to-path)
              prefixed-before-path (when before-path
                                     (if before-group?
                                       (mapv add-set-path-group-prefix before-path)
                                       (set-full-path->set-prefixed-full-path before-path)))

              set (assoc prev-set :name (join-set-path to-path))
              reorder? (= prefixed-from-path prefixed-to-path)
              sets'
              (if reorder?
                (d/oreorder-before sets
                                   (into [] (butlast prefixed-from-path))
                                   (last prefixed-from-path)
                                   set
                                   (last prefixed-before-path))
                (-> (if before-path
                      (d/oassoc-in-before sets prefixed-before-path prefixed-to-path set)
                      (d/oassoc-in sets prefixed-to-path set))
                    (d/dissoc-in prefixed-from-path)))]
          (TokensLib. sets'
                      (if reorder?
                        themes
                        (walk/postwalk
                         (fn [form]
                           (if (instance? TokenTheme form)
                             (update-set-name form (:name prev-set) (:name set))
                             form))
                         themes))
                      active-themes))
        (TokensLib. sets themes active-themes))))

  (move-set-group [this from-path to-path before-path before-group?]
    (let [prefixed-from-path (set-group-path->set-group-prefixed-path from-path)
          prev-set-group (get-in sets prefixed-from-path)]
      (if prev-set-group
        (let [from-path-str (join-set-path from-path)
              to-path-str (join-set-path to-path)
              prefixed-to-path (set-group-path->set-group-prefixed-path to-path)
              prefixed-before-path (when before-path
                                     (if before-group?
                                       (set-group-path->set-group-prefixed-path before-path)
                                       (set-full-path->set-prefixed-full-path before-path)))
              reorder? (= prefixed-from-path prefixed-to-path)
              sets'
              (if reorder?
                (d/oreorder-before sets
                                   (into [] (butlast prefixed-from-path))
                                   (last prefixed-from-path)
                                   prev-set-group
                                   (last prefixed-before-path))
                (-> (if before-path
                      (d/oassoc-in-before sets prefixed-before-path prefixed-to-path prev-set-group)
                      (d/oassoc-in sets prefixed-to-path prev-set-group))
                    (d/dissoc-in prefixed-from-path)
                    (d/oupdate-in prefixed-to-path (fn [sets]
                                                     (walk/prewalk
                                                      (fn [form]
                                                        (if (instance? TokenSet form)
                                                          (update form :name #(str to-path-str (str/strip-prefix % from-path-str)))
                                                          form))
                                                      sets)))))
              themes' (if reorder?
                        themes
                        (let [rename-sets-map (->> (get-sets-at-path this from-path)
                                                   (map (fn [set]
                                                          [(:name set) (str to-path-str (str/strip-prefix (:name set) from-path-str))]))
                                                   (into {}))]
                          (walk/postwalk
                           (fn [form]
                             (if (instance? TokenTheme form)
                               (update form :sets #(set (replace rename-sets-map %)))
                               form))
                           themes)))]
          (TokensLib. sets'
                      themes'
                      active-themes))
        (TokensLib. sets themes active-themes))))

  (get-set-tree [_]
    sets)

  (get-in-set-tree [_ path]
    (get-in sets path))

  (get-sets [_]
    (->> (tree-seq d/ordered-map? vals sets)
         (filter (partial instance? TokenSet))))

  (get-path-sets [_ path]
    (some->> (get-in sets (split-token-set-path path))
             (tree-seq d/ordered-map? vals)
             (filter (partial instance? TokenSet))))

  (get-sets-at-prefix-path [_ prefixed-path-str]
    (some->> (get-in sets (split-token-set-path prefixed-path-str))
             (tree-seq d/ordered-map? vals)
             (filter (partial instance? TokenSet))))

  (get-sets-at-path [_ path]
    (some->> (map add-set-path-group-prefix path)
             (get-in sets)
             (tree-seq d/ordered-map? vals)
             (filter (partial instance? TokenSet))))

  (rename-set-group [this path path-fname]
    (let [from-path-str (join-set-path path)
          to-path-str (-> (replace-last-path-name path path-fname)
                          (join-set-path))
          sets (get-sets-at-path this path)]
      (reduce
       (fn [lib set]
         (update-set lib (:name set) (fn [set']
                                       (update set' :name #(str to-path-str (str/strip-prefix % from-path-str))))))
       this sets)))

  (get-ordered-set-names [this]
    (map :name (get-sets this)))

  (set-count [this]
    (count (get-sets this)))

  (get-set [_ set-name]
    (let [path (set-name->prefixed-full-path set-name)]
      (get-in sets path)))

  (get-neighbor-set-name [this set-name index-offset]
    (let [sets (get-ordered-set-names this)
          index (d/index-of sets set-name)
          neighbor-set-name (when index
                              (nth sets (+ index-offset index) nil))]
      neighbor-set-name))

  ITokenThemes
  (add-theme [_ token-theme]
    (dm/assert! "expected valid token theme" (check-token-theme! token-theme))
    (TokensLib. sets
                (update themes (:group token-theme) d/oassoc (:name token-theme) token-theme)
                active-themes))

  (update-theme [this group name f]
    (let [theme (dm/get-in themes [group name])]
      (if theme
        (let [theme' (-> (make-token-theme (f theme))
                         (assoc :modified-at (dt/now)))
              group' (:group theme')
              name'  (:name theme')
              same-group? (= group group')
              same-name? (= name name')
              same-path? (and same-group? same-name?)]
          (check-token-theme! theme')
          (TokensLib. sets
                      (if same-path?
                        (update themes group' assoc name' theme')
                        (-> themes
                            (d/oassoc-in-before [group name] [group' name'] theme')
                            (d/dissoc-in [group name])))
                      (if same-path?
                        active-themes
                        (disj active-themes (token-theme-path group name)))))
        this)))

  (delete-theme [_ group name]
    (TokensLib. sets
                (d/dissoc-in themes [group name])
                (disj active-themes (token-theme-path group name))))

  (get-theme-tree [_]
    themes)

  (get-theme-groups [_]
    (into [] (comp
              (map key)
              (remove top-level-theme-group?))
          themes))

  (get-themes [_]
    (->> (tree-seq d/ordered-map? vals themes)
         (filter (partial instance? TokenTheme))))

  (theme-count [this]
    (count (get-themes this)))

  (get-theme [_ group name]
    (dm/get-in themes [group name]))

  (get-hidden-theme [this]
    (get-theme this hidden-token-theme-group hidden-token-theme-name))

  (set-active-themes [_ active-themes]
    (TokensLib. sets
                themes
                active-themes))

  (activate-theme [this group name]
    (if-let [theme (get-theme this group name)]
      (let [group-themes (->> (get themes group)
                              (map (comp theme-path val))
                              (into #{}))
            active-themes' (-> (set/difference active-themes group-themes)
                               (conj (theme-path theme)))]
        (TokensLib. sets
                    themes
                    active-themes'))
      this))

  (deactivate-theme [_ group name]
    (TokensLib. sets
                themes
                (disj active-themes (token-theme-path group name))))

  (theme-active? [_ group name]
    (contains? active-themes (token-theme-path group name)))

  (toggle-theme-active? [this group name]
    (if (theme-active? this group name)
      (deactivate-theme this group name)
      (activate-theme this group name)))

  (get-active-theme-paths [_]
    active-themes)

  (get-active-themes [this]
    (into
     (list)
     (comp
      (filter (partial instance? TokenTheme))
      (filter #(theme-active? this (:group %) (:name %))))
     (tree-seq d/ordered-map? vals themes)))

  ITokensLib
  (set-path-exists? [_ set-path]
    (some? (get-in sets (set-full-path->set-prefixed-full-path set-path))))

  (set-group-path-exists? [_ set-path]
    (some? (get-in sets (set-group-path->set-group-prefixed-path set-path))))

  (add-token-in-set [this set-name token]
    (dm/assert! "expected valid token instance" (check-token! token))
    (update-set this set-name #(add-token % token)))

  (update-token-in-set [this set-name token-name f]
    (update-set this set-name #(update-token % token-name f)))

  (delete-token-from-set [this set-name token-name]
    (update-set this set-name #(delete-token % token-name)))

  (toggle-set-in-theme [this theme-group theme-name set-name]
    (if-let [_theme (get-in themes theme-group theme-name)]
      (TokensLib. sets
                  (d/oupdate-in themes [theme-group theme-name]
                                #(toggle-set % set-name))
                  active-themes)
      this))

  (get-active-themes-set-names [this]
    (into #{}
          (mapcat :sets)
          (get-active-themes this)))

  (sets-at-path-all-active? [this group-path]
    (let [active-set-names (get-active-themes-set-names this)
          prefixed-path-str (set-group-path->set-group-prefixed-path-str group-path)]
      (if (seq active-set-names)
        (let [path-active-set-names (->> (get-sets-at-prefix-path this prefixed-path-str)
                                         (map :name)
                                         (into #{}))
              difference (set/difference path-active-set-names active-set-names)]
          (cond
            (empty? difference) :all
            (seq (set/intersection path-active-set-names active-set-names)) :partial
            :else :none))
        :none)))

  (get-active-themes-set-tokens [this]
    (let [sets-order (get-ordered-set-names this)
          active-themes (get-active-themes this)
          order-theme-set (fn [theme]
                            (filter #(contains? (set (:sets theme)) %) sets-order))]
      (reduce
       (fn [tokens theme]
         (reduce
          (fn [tokens' cur]
            (merge tokens' (:tokens (get-set this cur))))
          tokens (order-theme-set theme)))
       (d/ordered-map) active-themes)))

  (encode-dtcg [_]
    (let [themes (into []
                       (comp
                        (filter #(and (instance? TokenTheme %)
                                      (not (hidden-temporary-theme? %))))
                        (map (fn [token-theme]
                               (let [theme-map (->> token-theme
                                                    (into {})
                                                    walk/stringify-keys)]
                                 (-> theme-map
                                     (set/rename-keys  {"sets" "selectedTokenSets"})
                                     (update "selectedTokenSets" (fn [sets]
                                                                   (->> (for [s sets]
                                                                          [s "enabled"])
                                                                        (into {})))))))))
                       (tree-seq d/ordered-map? vals themes))
          name-set-tuples (->> sets
                               (tree-seq d/ordered-map? vals)
                               (filter (partial instance? TokenSet))
                               (map (fn [token-set]
                                      [(:name token-set) (get-dtcg-tokens-tree token-set)])))
          ordered-set-names (map first name-set-tuples)
          sets (into {} name-set-tuples)]
      (-> sets
          (assoc "$themes" themes)
          (assoc-in ["$metadata" "tokenSetOrder"] ordered-set-names))))

  (decode-dtcg-json [_ parsed-json]
    (let [metadata (get parsed-json "$metadata")
          sets-data (dissoc parsed-json "$themes" "$metadata")
          themes-data (->> (get parsed-json "$themes")
                           (map (fn [theme]
                                  (-> theme
                                      (set/rename-keys {"selectedTokenSets" "sets"})
                                      (update "sets" keys)))))
          set-order (get metadata "tokenSetOrder")
          name->pos (into {} (map-indexed (fn [idx itm] [itm idx]) set-order))
          sets-data' (sort-by (comp name->pos first) sets-data)
          lib (make-tokens-lib)
          lib' (reduce
                (fn [lib [set-name tokens]]
                  (add-set lib (make-token-set
                                :name set-name
                                :tokens (flatten-nested-tokens-json tokens ""))))
                lib sets-data')]
      (if-let [themes-data (seq themes-data)]
        (reduce
         (fn [lib {:strs [name group description is-source modified-at sets]}]
           (add-theme lib (TokenTheme. name
                                       (or group "")
                                       description
                                       (some? is-source)
                                       (or (some-> modified-at
                                                   (dt/parse-instant))
                                           (dt/now))
                                       (set sets))))
         lib' themes-data)
        lib')))

  (get-all-tokens [this]
    (reduce
     (fn [tokens' set]
       (into tokens' (map (fn [x] [(:name x) x]) (get-tokens set))))
     {} (get-sets this)))

  (validate [_]
    (and (valid-token-sets? sets)
         (valid-token-themes? themes)
         (valid-active-token-themes? active-themes))))

(defn valid-tokens-lib?
  [o]
  (and (instance? TokensLib o)
       (validate o)))

(defn check-tokens-lib!
  [lib]
  (dm/assert!
   "expected valid tokens lib"
   (valid-tokens-lib? lib)))

(defn make-tokens-lib
  "Create an empty or prepopulated tokens library."
  ([]
   ;; NOTE: is possible that ordered map is not the most apropriate
   ;; data structure and maybe we need a specific that allows us an
   ;; easy way to reorder it, or just store inside Tokens data
   ;; structure the data and the order separately as we already do
   ;; with pages and pages-index.
   (make-tokens-lib :sets (d/ordered-map)
                    :themes (d/ordered-map)
                    :active-themes #{}))

  ([& {:keys [sets themes active-themes]}]
   (let [tokens-lib (TokensLib. sets themes (or active-themes #{}))]

     (dm/assert!
      "expected valid tokens lib"
      (valid-tokens-lib? tokens-lib))

     tokens-lib)))

(defn ensure-tokens-lib
  [tokens-lib]
  (or tokens-lib (make-tokens-lib)))

(defn decode-dtcg
  [encoded-json]
  (-> (make-tokens-lib)
      (decode-dtcg-json encoded-json)))

(def type:tokens-lib
  {:type ::tokens-lib
   :pred valid-tokens-lib?
   :type-properties {:encode/json encode-dtcg
                     :decode/json decode-dtcg}})

(sm/register! ::tokens-lib type:tokens-lib)

;; === Serialization handlers for RPC API (transit) and database (fressian)

(t/add-handlers!
 {:id "penpot/tokens-lib"
  :class TokensLib
  :wfn deref
  :rfn #(make-tokens-lib %)}

 {:id "penpot/token-set"
  :class TokenSet
  :wfn #(into {} %)
  :rfn #(make-token-set %)}

 {:id "penpot/token-theme"
  :class TokenTheme
  :wfn #(into {} %)
  :rfn #(make-token-theme %)}

 {:id "penpot/token"
  :class Token
  :wfn #(into {} %)
  :rfn #(make-token %)})

#?(:clj
   (fres/add-handlers!
    {:name "penpot/token/v1"
     :class Token
     :wfn (fn [n w o]
            (fres/write-tag! w n 1)
            (fres/write-object! w (into {} o)))
     :rfn (fn [r]
            (let [obj (fres/read-object! r)]
              (map->Token obj)))}

    {:name "penpot/token-set/v1"
     :class TokenSet
     :wfn (fn [n w o]
            (fres/write-tag! w n 1)
            (fres/write-object! w (into {} o)))
     :rfn (fn [r]
            (let [obj (fres/read-object! r)]
              (map->TokenSet obj)))}

    {:name "penpot/token-theme/v1"
     :class TokenTheme
     :wfn (fn [n w o]
            (fres/write-tag! w n 1)
            (fres/write-object! w (into {} o)))
     :rfn (fn [r]
            (let [obj (fres/read-object! r)]
              (map->TokenTheme obj)))}

    {:name "penpot/tokens-lib/v1"
     :rfn (fn [r]
            (let [;; Migrate sets tree without prefix to new format
                  prev-sets (->> (fres/read-object! r)
                                 (tree-seq d/ordered-map? vals)
                                 (filter (partial instance? TokenSet)))
                  sets (-> (make-tokens-lib)
                           (add-sets prev-sets)
                           (deref)
                           :sets)
                  _set-groups   (fres/read-object! r)
                  themes        (fres/read-object! r)
                  active-themes (fres/read-object! r)]
              (->TokensLib sets themes active-themes)))}

    {:name "penpot/tokens-lib/v1.1"
     :class TokensLib
     :wfn (fn [n w o]
            (fres/write-tag! w n 3)
            (fres/write-object! w (.-sets o))
            (fres/write-object! w (.-themes o))
            (fres/write-object! w (.-active-themes o)))
     :rfn (fn [r]
            (let [sets          (fres/read-object! r)
                  themes        (fres/read-object! r)
                  active-themes (fres/read-object! r)]
              (->TokensLib sets themes active-themes)))}))
