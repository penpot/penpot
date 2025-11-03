;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.types.tokens-lib
  (:require
   #?(:clj [app.common.fressian :as fres])
   #?(:clj [clojure.data.json :as json])
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.files.helpers :as cfh]
   [app.common.path-names :as cpn]
   [app.common.schema :as sm]
   [app.common.schema.generators :as sg]
   [app.common.time :as ct]
   [app.common.transit :as t]
   [app.common.types.token :as cto]
   [app.common.uuid :as uuid]
   [clojure.core.protocols :as cp]
   [clojure.datafy :refer [datafy]]
   [clojure.pprint :as pp]
   [clojure.set :as set]
   [clojure.walk :as walk]
   [cuerdas.core :as str]))

;; === Common

(defprotocol IValidation
  (valid? [_] "check if this data structure is valid, returns true or false")
  (check [_] "check if this data structure is valid, raises exception or self"))

(defprotocol INamedItem
  "Protocol for items that have an id, a name, a description and a modified date."
  (get-id [_] "Get the id of the item.")
  (get-name [_] "Get the name of the item.")
  (get-description [_] "Get the description of the item.")
  (get-modified-at [_] "Get the description of the item.")
  (rename [_ new-name] "Change the name of the item.")
  (reid [_ new-id] "Change the id of the item.")
  (set-description [_ new-description] "Set the description of the item."))

;; Provide an implementation for nil type, so it works when functions are
;; called with nil as argument.
(extend-type nil
  INamedItem
  (get-id [_] nil)
  (get-name [_] nil)
  (get-description [_] nil)
  (get-modified-at [_] nil)
  (rename [_ _] nil)
  (reid [_ _] nil)
  (set-description [_ _] nil))

;; === Token

(defrecord Token [id name type value description modified-at]
  cp/Datafiable
  (datafy [this] (into {} this))

  INamedItem
  (get-id [_]
    id)

  (get-name [_]
    name)

  (get-description [_]
    description)

  (get-modified-at [_]
    modified-at)

  (rename [this new-name]
    (assoc this :name new-name))

  (reid [this new-id]
    (assoc this :id new-id))

  (set-description [this new-description]
    (assoc this :description new-description)))

(defmethod pp/simple-dispatch Token
  [^Token obj]
  (.write *out* "#penpot/token ")
  (pp/pprint-newline :miser)
  (pp/pprint (datafy obj)))

#?(:clj
   (do
     (defmethod print-method Token
       [^Token this ^java.io.Writer w]
       (.write w "#penpot/token ")
       (print-method (datafy this) w))

     (defmethod print-dup Token
       [^Token this ^java.io.Writer w]
       (print-method this w)))

   :cljs
   (extend-type Token
     cljs.core/IPrintWithWriter
     (-pr-writer [this writer opts]
       (-write writer "#penpot/token ")
       (-pr-writer (datafy this) writer opts))

     cljs.core/IEncodeJS
     (-clj->js [this]
       (clj->js (datafy this)))))

(defn token?
  [o]
  (instance? Token o))

(def schema:token-attrs
  [:map {:title "Token"}
   [:id ::sm/uuid]
   [:name cto/token-name-ref]
   [:type [::sm/one-of cto/token-types]]
   [:value ::sm/any]
   [:description {:optional true} :string]
   [:modified-at {:optional true} ::ct/inst]])

(declare make-token)

(def schema:token
  [:and {:gen/gen (->> (sg/generator schema:token-attrs)
                       (sg/fmap #(make-token %)))}
   (sm/required-keys schema:token-attrs)
   [:fn token?]])

(def ^:private check-token-attrs
  (sm/check-fn schema:token-attrs :hint "expected valid params for token"))

(def check-token
  (sm/check-fn schema:token :hint "expected valid token"))

(defn make-token
  [& {:as attrs}]
  (-> attrs
      (update :id #(or % (uuid/next)))
      (update :modified-at #(or % (ct/now)))
      (update :description d/nilv "")
      (check-token-attrs)
      (map->Token)))

(def ^:private token-separator ".")

(defn get-token-path
  [token]
  (cpn/split-path (:name token) :separator token-separator))

(defn group-by-type [tokens]
  (let [tokens' (if (or (map? tokens)
                        (d/ordered-map? tokens))
                  (vals tokens)
                  tokens)]
    (group-by :type tokens')))

;; === Token Set

(defprotocol ITokenSet
  ;; TODO: the tokens tree is planned to be moved to the TokensLib. So this protocol will be no
  ;; longer needed. For now, it's kept but it should only be used internally in the TokensLib.
  ;; The - suffix is to remind it.
  (add-token- [_ token] "add a token at the end of the list")
  (update-token- [_ id f] "update a token in the list")
  (delete-token- [_ id] "delete a token from the list")
  (get-token- [_ id] "get a token by its id")
  (get-token-by-name- [_ name] "get a token by its name")
  (get-tokens- [_] "return a map of tokens in the set, indexed by token-name"))

;; TODO: this structure is temporary. It's needed to be able to migrate TokensLib
;; from 1.2 to 1.3 when TokenSet datatype was changed to a deftype. This should
;; be removed after migrations are consolidated.
(defrecord TokenSetLegacy [id name description modified-at tokens])

(deftype TokenSet [id name description modified-at tokens]
  Object
  (equals [_ other]
    (and (instance? TokenSet other)
         (= id (.-id ^TokenSet other))
         (= name (.-name ^TokenSet other))
         (= description (.-description ^TokenSet other))
         (= modified-at (.-modified-at ^TokenSet other))
         (= tokens (.-tokens ^TokenSet other))))

  #?@(:cljs [cljs.core/IEquiv
             (-equiv [this other] (.equals ^TokenSet this other))])

  cp/Datafiable
  (datafy [_]
    {:id id
     :name name
     :description description
     :modified-at modified-at
     :tokens tokens})

  #?@(:clj
      [json/JSONWriter
       (-write [this writter options] (json/-write (datafy this) writter options))])

  INamedItem
  (get-id [_]
    id)

  (get-name [_]
    name)

  (get-description [_]
    description)

  (get-modified-at [_]
    modified-at)

  (rename [_ new-name]
    (TokenSet. id
               new-name
               description
               (ct/now)
               tokens))

  (reid [_ new-id]
    (TokenSet. new-id
               name
               description
               (ct/now)
               tokens))

  (set-description [_ new-description]
    (TokenSet. id
               name
               (d/nilv new-description "")
               (ct/now)
               tokens))

  ITokenSet
  (add-token- [_ token]
    (let [token (check-token token)]
      (TokenSet. id
                 name
                 description
                 (ct/now)
                 (assoc tokens (:name token) token))))

  (update-token- [this token-id f]
    (assert (uuid? token-id) "expected uuid for `token-id`")
    (if-let [token (get-token- this token-id)]
      (let [token' (-> (make-token (f token))
                       (assoc :modified-at (ct/now)))]
        (TokenSet. id
                   name
                   description
                   (ct/now)
                   (if (= (:name token) (:name token'))
                     (assoc tokens (:name token') token')
                     (-> tokens
                         (d/oassoc-before (:name token) (:name token') token')
                         (dissoc (:name token))))))
      this))

  (delete-token- [this token-id]
    (assert (uuid? token-id) "expected uuid for `token-id`")
    (let [token (get-token- this token-id)]
      (TokenSet. id
                 name
                 description
                 (ct/now)
                 (dissoc tokens (:name token)))))

  (get-token- [_ token-id]
    (assert (uuid? token-id) "expected uuid for `token-id`")
    (some #(when (= (:id %) token-id) %) ;; TODO: this will be made in an efficient way when
          (vals tokens)))                ;;       we refactor the tokens lib internal structure

  (get-token-by-name- [_ name]
    (assert (string? name) "expected string for `name`")
    (get tokens name))

  (get-tokens- [_]
    tokens))

(defmethod pp/simple-dispatch TokenSet [^TokenSet obj]
  (.write *out* "#penpot/token-set ")
  (pp/pprint-newline :miser)
  (pp/pprint (datafy obj)))

#?(:clj
   (do
     (defmethod print-method TokenSet
       [^TokenSet this ^java.io.Writer w]
       (.write w "#penpot/token-set ")
       (print-method (datafy this) w))

     (defmethod print-dup TokenSet
       [^TokenSet this ^java.io.Writer w]
       (print-method this w)))

   :cljs
   (extend-type TokenSet
     cljs.core/IPrintWithWriter
     (-pr-writer [this writer opts]
       (-write writer "#penpot/token-set ")
       (-pr-writer (datafy this) writer opts))

     cljs.core/IEncodeJS
     (-clj->js [this]
       (clj->js (datafy this)))))

(defn token-set?
  [o]
  (instance? TokenSet o))

(defn token-set-legacy?
  [o]
  (instance? TokenSetLegacy o))

(def schema:token-set-attrs
  [:map {:title "TokenSet"}
   [:id ::sm/uuid]
   [:name :string]
   [:description {:optional true} :string]
   [:modified-at {:optional true} ::ct/inst]
   [:tokens {:optional true
             :gen/gen (->> (sg/map-of (sg/generator ::sm/text)
                                      (sg/generator schema:token))
                           (sg/fmap #(into (d/ordered-map) %)))}
    [:and
     [:map-of {:gen/max 5
               :decode/json (fn [v]
                              (cond
                                (d/ordered-map? v)
                                v

                                (map? v)
                                (into (d/ordered-map) v)

                                :else
                                v))}
      :string schema:token]
     [:fn d/ordered-map?]]]])

(declare make-token-set)

(def schema:token-set
  [:schema {:gen/gen (->> (sg/generator schema:token-set-attrs)
                          (sg/fmap #(make-token-set %)))}
   (sm/required-keys schema:token-set-attrs)])

(sm/register! ::token-set schema:token-set) ;; need to register for the recursive schema of token-sets

(def ^:private check-token-set-attrs
  (sm/check-fn schema:token-set-attrs :hint "expected valid params for token-set"))

(def check-token-set
  (sm/check-fn schema:token-set :hint "expected valid token set"))

(defn map->token-set
  [& {:as attrs}]
  (TokenSet. (:id attrs)
             (:name attrs)
             (:description attrs)
             (:modified-at attrs)
             (:tokens attrs)))

(defn make-token-set
  [& {:as attrs}]
  (-> attrs
      (update :id #(or % (uuid/next)))
      (update :modified-at #(or % (ct/now)))
      (update :tokens #(into (d/ordered-map) %))
      (update :description d/nilv "")
      (check-token-set-attrs)
      (map->token-set)))

(def ^:private set-prefix "S-")

(def ^:private set-group-prefix "G-")

(def ^:private set-separator "/")

(defn get-set-path
  [token-set]
  (cpn/split-path (get-name token-set) :separator set-separator))

(defn split-set-name
  [name]
  (cpn/split-path name :separator set-separator))

(defn join-set-path [path]
  (cpn/join-path path :separator set-separator :with-spaces? false))

(defn normalize-set-name
  "Normalize a set name (ensure that there are no extra spaces, like ' group /  set' -> 'group/set').

  If `relative-to` is provided, the normalized name will preserve the same group prefix as reference name."
  ([name]
   (-> (split-set-name name)
       (cpn/join-path :separator set-separator :with-spaces? false)))
  ([name relative-to]
   (-> (concat (butlast (split-set-name relative-to))
               (split-set-name name))
       (cpn/join-path :separator set-separator :with-spaces? false))))

(defn replace-last-path-name
  "Replaces the last element in a `path` vector with `name`."
  [path name]
  (-> (into [] (drop-last path))
      (conj name)))

;; The following functions will be removed after refactoring the internal structure of TokensLib,
;; since we'll no longer need group prefixes to differentiate between sets and set-groups.

(defn- split-set-str-path-prefix
  "Split set-path

  E.g.: \"S-some-set\"   -> [\"S-\" \"some-set\"]
        \"G-some-group\" -> [\"G-\" \"some-group\"]"
  [path-str]
  (some->> path-str
           (re-matches #"^([SG]-)(.*)")
           (rest)))

(defn- add-set-path-prefix [set-name-str]
  (str set-prefix set-name-str))

(defn- add-set-path-group-prefix [group-path-str]
  (str set-group-prefix group-path-str))

(defn- set-full-path->set-prefixed-full-path
  "Returns token-set paths with prefixes to differentiate between sets and set-groups.

  Sets will be prefixed with `set-prefix` (S-).
  Set groups will be prefixed with `set-group-prefix` (G-)."
  [full-path]
  (let [set-path (mapv add-set-path-group-prefix (butlast full-path))
        set-name (add-set-path-prefix (last full-path))]
    (conj set-path set-name)))

(defn- set-group-path->set-group-prefixed-path
  "Adds `set-group-prefix` (G-) to the `path` vector elements."
  [path]
  (mapv add-set-path-group-prefix path))

(defn- set-group-path->set-group-prefixed-path-str
  [path]
  (-> (set-group-path->set-group-prefixed-path path)
      (join-set-path)))

(defn add-set-group-prefix [group-path]
  (str set-group-prefix group-path))

(defn- set-name->prefixed-full-path [name-str]
  (-> (split-set-name name-str)
      (set-full-path->set-prefixed-full-path)))

(defn- get-set-prefixed-path [token-set]
  (let [path (cpn/split-path (get-name token-set) :separator set-separator)]
    (set-full-path->set-prefixed-full-path path)))

(defn tokens-tree
  "Convert tokens into a nested tree with their name as the path.
  Optionally use `update-token-fn` option to transform the token."
  [tokens & {:keys [update-token-fn]
             :or {update-token-fn identity}}]
  (reduce-kv (fn [acc _ token]
               (let [path (get-token-path token)]
                 (assoc-in acc path (update-token-fn token))))
             {} tokens))

(defn backtrace-tokens-tree
  "Convert tokens into a nested tree with their name as the path.
  Generates a uuid per token to backtrace a token from an external source (StyleDictionary).
  The backtrace can't be the name as the name might not exist when the user is creating a token."
  [tokens]
  (reduce
   (fn [acc [_ token]]
     (let [temp-id (random-uuid)
           token   (assoc token :temp/id temp-id)
           path    (get-token-path token)]
       (-> acc
           (assoc-in (concat [:tokens-tree] path) token)
           (assoc-in [:ids temp-id] token))))
   {:tokens-tree {} :ids {}}
   tokens))

;; === TokenSets (collection)

(defprotocol ITokenSets
  "Collection of sets and set groups."
  (add-set [_ token-set]
    "Add a set to the library, at the end of the list")
  (update-set [_ id f]
    "Modify a set in the library")
  (delete-set [_ id]
    "Delete a set in the library and remove it from all themes")
  (move-set [_ from-path to-path before-path before-group?]
    "Move token set at `from-path` to `to-path` and order it before `before-path` with `before-group?`")
  (move-set-group [_ from-path to-path before-path before-group?]
    "Move token set group at `from-path` to `to-path` and order it before `before-path` with `before-group?`.")
  (rename-set-group [_ from-path-str to-path-str]
    "Renames set groups and all child set names from `from-path-str` to `to-path-str`")
  (set-count [_]
    "Get the total number if sets in the library")
  (get-set [_ id]
    "Get a set looking by id")
  (get-set-by-name [_ name]
    "Get a set looking by name")
  (get-sets [_]
    "Get an ordered sequence of all sets in the library")
  (get-set-names [_]
    "Get an ordered sequence of all sets names in the library")
  (get-set-tree [_]
    "Get a nested tree of all sets in the library")
  (get-sets-at-path [_ path-str]
    "Get an ordered sequence of sets under `path` in the library"))

(def ^:private schema:token-set-node
  [:schema {:registry {::node
                       [:or [:fn token-set?]
                        [:and
                         [:map-of {:gen/max 5} :string [:ref ::node]]
                         [:fn d/ordered-map?]]]}}
   [:ref ::node]])

(defn- not-repeated-ids
  [sets]
  ;; TODO: this check will not be necessary after refactoring the internal structure of TokensLib
  ;; since we'll use a map of sets indexed by id. Thus, it should be removed.
  (let [ids  (->> (tree-seq d/ordered-map? vals sets)
                  (filter (partial instance? TokenSet))
                  (map get-id))
        ids' (set ids)]
    (= (count ids) (count ids'))))

(def ^:private schema:token-sets
  [:and {:title "TokenSets"}
   [:map-of :string  schema:token-set-node]
   [:fn d/ordered-map?]
   [:fn not-repeated-ids]])

;; === TokenTheme

(defprotocol ITokenTheme
  (set-sets [_ set-names] "set the active token sets")
  (enable-set [_ set-name] "enable one set in theme")
  (enable-sets [_ set-names] "enable several sets in theme")
  (disable-set [_ set-name] "disable one set in theme")
  (disable-sets [_ set-names] "disable several sets in theme")
  (toggle-set [_ set-name] "toggle a set enabled / disabled in the theme")
  (update-set-name [_ prev-set-name set-name] "update set-name when it exists")
  (theme-matches-group-name [_ group name] "if a theme matches the given group & name")
  (hidden-theme? [_] "if a theme is the (from the user ui) hidden temporary theme"))

(def hidden-theme-id
  uuid/zero)

(def hidden-theme-group
  "")

(def hidden-theme-name
  "__PENPOT__HIDDEN__TOKEN__THEME__")

(defrecord TokenTheme [id name group description is-source external-id modified-at sets]
  cp/Datafiable
  (datafy [this] (into {} this))

  INamedItem
  (get-id [_]
    id)

  (get-name [_]
    name)

  (get-description [_]
    description)

  (get-modified-at [_]
    modified-at)

  (rename [this new-name]
    (assoc this :name new-name))

  (reid [this new-id]
    (assoc this :id new-id))

  (set-description [this new-description]
    (assoc this :description new-description))

  ITokenTheme
  (set-sets [_ set-names]
    (TokenTheme. id
                 name
                 group
                 description
                 is-source
                 external-id
                 (ct/now)
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
      (TokenTheme. id
                   name
                   group
                   description
                   is-source
                   external-id
                   (ct/now)
                   (conj (disj sets prev-set-name) set-name))
      this))

  (theme-matches-group-name [this group name]
    (and (= (:group this) group)
         (= (:name this) name)))

  (hidden-theme? [this]
    (theme-matches-group-name this hidden-theme-group hidden-theme-name)))

(defmethod pp/simple-dispatch TokenTheme
  [^TokenTheme obj]
  (.write *out* "#penpot/token-theme ")
  (pp/pprint-newline :miser)
  (pp/pprint (datafy obj)))

#?(:clj
   (do
     (defmethod print-method TokenTheme
       [^TokenTheme this ^java.io.Writer w]
       (.write w "#penpot/token-theme ")
       (print-method (datafy this) w))

     (defmethod print-dup TokenTheme
       [^TokenTheme this ^java.io.Writer w]
       (print-method this w)))

   :cljs
   (extend-type TokenTheme
     cljs.core/IPrintWithWriter
     (-pr-writer [this writer opts]
       (-write writer "#penpot/token-theme ")
       (-pr-writer (datafy this) writer opts))

     cljs.core/IEncodeJS
     (-clj->js [this]
       (clj->js (datafy this)))))

(defn token-theme?
  [o]
  (instance? TokenTheme o))

(def schema:token-theme-attrs
  [:map {:title "TokenTheme"}
   [:id ::sm/uuid]
   [:name :string]
   [:group {:optional true} :string]
   [:description {:optional true} :string]
   [:is-source {:optional true} :boolean]
   [:external-id {:optional true} :string]
   [:modified-at {:optional true} ::ct/inst]
   [:sets {:optional true} [:set {:gen/max 5} :string]]])

(def schema:token-theme
  [:and
   (sm/required-keys schema:token-theme-attrs)
   [:fn token-theme?]])

(def ^:private check-token-theme-attrs
  (sm/check-fn schema:token-theme-attrs :hint "expected valid params for token-theme"))

(def check-token-theme
  (sm/check-fn schema:token-theme :hint "expected a valid token-theme"))

(def ^:private top-level-theme-group-name
  "Top level theme groups have an empty string as the theme group."
  "")

(defn top-level-theme-group? [group]
  (= group top-level-theme-group-name))

(defn make-token-theme
  [& {:as attrs}]
  (let [new-id (uuid/next)]
    (-> attrs
        (update :id (fn [id]
                      (-> (if (string? id)    ;; TODO: probably this may be deleted in some time, when we may be sure
                            (uuid/parse* id)  ;;       that no file exists that has not been correctly migrated to
                            id)               ;;       convert :id into :external-id
                          (d/nilv new-id))))
        (update :group d/nilv top-level-theme-group-name)
        (update :description d/nilv "")
        (update :is-source d/nilv false)
        (update :external-id #(or % (str new-id)))
        (update :modified-at #(or % (ct/now)))
        (update :sets set)
        (check-token-theme-attrs)
        (map->TokenTheme))))

(defn make-hidden-theme
  [& {:as attrs}]
  (-> attrs
      (assoc :id uuid/zero)
      (assoc :external-id "")
      (assoc :group hidden-theme-group)
      (assoc :name hidden-theme-name)
      (make-token-theme)))

(def ^:private theme-separator "/")

(defn- join-theme-path [group name]
  (cpn/join-path [group name] :separator theme-separator :with-spaces? false))

(defn get-theme-path [theme]
  (join-theme-path (:group theme) (:name theme)))

(defn split-theme-path [path]
  (cpn/split-group-name path
                        :separator theme-separator
                        :with-spaces? false))

(def hidden-theme-path
  (join-theme-path hidden-theme-group hidden-theme-name))

;; === TokenThemes (collection)

(defprotocol ITokenThemes
  "Collection of themes in groups"
  (add-theme [_ token-theme] "add a theme to the library, at the end")
  (update-theme [_ id f] "modify a theme in the ilbrary")
  (delete-theme [_ id] "delete a theme in the library")
  (theme-count [_] "get the total number if themes in the library")
  (get-theme-tree [_] "get a nested tree of all themes in the library")
  (get-themes [_] "get an ordered sequence of all themes in the library")
  (get-theme [_ id] "get one theme looking for id")
  (get-theme-by-name [_ group name] "get one theme looking for group and name")
  (get-theme-groups [_] "get a sequence of group names by order")
  (get-active-theme-paths [_] "get the active theme paths")
  (get-active-themes [_] "get an ordered sequence of active themes in the library")
  (set-active-themes  [_ active-themes] "set active themes in library")
  (theme-active? [_ id] "predicate if token theme is active")
  (activate-theme [_ id] "adds theme from the active-themes")
  (deactivate-theme [_ id] "removes theme from the active-themes")
  (toggle-theme-active? [_ id] "toggles theme in the active-themes")
  (get-hidden-theme [_] "get the hidden temporary theme"))

(def schema:token-themes
  [:and
   [:map-of {:title "TokenThemes"}
    :string [:and [:map-of :string schema:token-theme]
             [:fn d/ordered-map?]]]
   [:fn d/ordered-map?]])

(def ^:private schema:active-themes
  [:set :string])

(defn walk-sets-tree-seq
  "Walk sets tree as a flat list.

  Options:
    `:skip-children-pred`: predicate to skip iterating over a set groups children by checking the path of the set group
    `:new-editing-set-path`: append a an item with `:new?` at the given path"
  [nodes & {:keys [skip-children-pred new-editing-set-path]
            :or {skip-children-pred (constantly false)}}]
  (let [walk (fn walk [node {:keys [parent depth]
                             :or {parent []
                                  depth 0}
                             :as opts}]
               (lazy-seq
                (if (d/ordered-map? node)
                  (let [root (cond-> node
                               (= [] new-editing-set-path) (assoc :new? true))]
                    (mapcat #(walk % opts) root))
                  (let [[k v] node]
                    (cond
                      ;; New set
                      (= :new? k) [{:new? true
                                    :group? false
                                    :parent-path parent
                                    :depth depth}]

                      ;; Set
                      (and v (instance? TokenSet v))
                      [{:group? false
                        :path (split-set-name (get-name v))
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
                        (if (skip-children-pred path)
                          [item]
                          (let [v' (cond-> v
                                     (= path new-editing-set-path) (assoc :new? true))]
                            (cons item (mapcat #(walk % (assoc opts :parent path :depth (inc depth))) v'))))))))))]
    (walk (or nodes (d/ordered-map)) nil)))

(defn sets-tree-seq
  "Return a lazy sequence that walks through the tokens sets tree in a depth-first order. It returns a node for each
   group and set. The nodes contain:
    - is-group: true if the node is a group, false if it's a set.
    - path: vector with the path of the group or set (without prefixes).
    - depth: depth in the tree, starting from 0.
    - index: index in the sequence, starting from 0.
    - id: the uuid if it's a set, and the path string if it's a group.
    - token-set: the TokenSet instance if it's a set.

  Options:
    `:skip-children-pred`: predicate that is given a node path. If it returns true, the children of that node are skipped.
    `:new-at-path`: append at the given path a node with `:is-new true` and a newly created set."

  [tree & {:keys [skip-children-pred new-at-path]
           :or {skip-children-pred (constantly false)}}]
  (let [walk (fn walk [[k v :as node] parent-path depth]
               (lazy-seq
                (cond
                  ;; New set
                  (= :is-new k)
                  (let [token-set (make-token-set :name (if (empty? parent-path)
                                                          ""
                                                          (join-set-path parent-path)))]
                    [{:is-new true
                      :is-group false
                      :path (split-set-name (get-name token-set))
                      :depth depth
                      :id (get-id token-set)
                      :token-set token-set}])

                  ;; Set
                  (and v (instance? TokenSet v))
                  [{:is-group false
                    :path (split-set-name (get-name v))
                    :depth depth
                    :id (get-id v)
                    :token-set v}]

                  ;; Set group
                  (and v (d/ordered-map? v))
                  (let [unprefixed-path (last (split-set-str-path-prefix k))
                        path (conj parent-path unprefixed-path)
                        item {:is-group true
                              :path path
                              :depth depth
                              :id (join-set-path path)}]

                    (if (skip-children-pred path)
                      [item]
                      (let [v (cond-> v
                                (= path new-at-path)
                                (assoc :is-new true))]
                        (cons item
                              (mapcat #(walk % path (inc depth)) v))))))))

        tree (cond-> tree
               (= [] new-at-path)
               (assoc :is-new true))]
    (->> tree
         (mapcat #(walk % [] 0))
         (map-indexed (fn [index item]
                        (assoc item :index index))))))

;; === Tokens Lib

(declare make-tokens-lib)
(declare ^:private check-tokens-lib-map)
(declare ^:private valid-tokens-lib-map?)

(defprotocol ITokensLib
  "A library of tokens, sets and themes."
  (empty-lib? [_] "True if the lib does not contain any token, set or theme")
  (set-path-exists? [_ path] "if a set at `path` exists")
  (set-group-path-exists? [_ path] "if a set group at `path` exists")
  (add-token [_ set-id token] "add token to a set")
  (get-token [_ set-id token-id] "get token in a set")
  (get-token-by-name [_ set-name token-name] "get token in a set searching by set and token names")
  (update-token [_ set-id token-id f] "update a token in a set")
  (delete-token [_ set-id token-id] "delete a token from a set")
  (toggle-set-in-theme [_ theme-id set-name] "toggle a set used / not used in a theme")
  (get-active-themes-set-names [_] "set of set names that are active in the the active themes")
  (sets-at-path-all-active? [_ group-path] "compute active state for child sets at `group-path`.
Will return a value that matches this schema:
`:none`    None of the nested sets are active
`:all`     All of the nested sets are active
`:partial` Mixed active state of nested sets")
  (get-tokens-in-active-sets [_] "set of set names that are active in the the active themes")
  (get-all-tokens [_] "all tokens in the lib")
  (get-tokens [_ set-id] "return a map of tokens in the set, indexed by token-name"))

(declare parse-multi-set-dtcg-json)
(declare export-dtcg-json)

(deftype TokensLib [sets themes active-themes]
  ;; This is to convert the TokensLib to a plain map, for debugging or unit tests.
  cp/Datafiable
  (datafy [_]
    {:sets sets
     :themes themes
     :active-themes active-themes})

  #?@(:clj
      [json/JSONWriter
       (-write [this writter options] (json/-write (export-dtcg-json this) writter options))])

  ITokenSets
  ;  Naming conventions:
  ;  (TODO: this will disappear after refactoring the internal structure of TokensLib).
  ;    Set name:                the complete name as a string, without prefix \"some-group/some-subgroup/some-set\".
  ;    Set final name or fname: the last part of the name \"some-set\".
  ;    Set path:                the groups part of the name, as a vector [\"some-group\" \"some-subgroup\"].
  ;    Set path str:            the set path as a string \"some-group/some-subgroup\".
  ;    Set full path:           the path including the fname, as a vector [\"some-group\", \"some-subgroup\", \"some-set\"].
  ;    Set full path str:       the set full path as a string \"some-group/some-subgroup/some-set\".

  ;    Set prefix:                        the two-characters prefix added to a full path item \"G-\" / \"S-\".
  ;    Prefixed set path or ppath:        a path wit added prefixes [\"G-some-group\", \"G-some-subgroup\"].
  ;    Prefixed set full path or pfpath:  a full path wit prefixes [\"G-some-group\", \"G-some-subgroup\", \"S-some-set\"].
  ;    Prefixed set final name or pfname: a final name with prefix \"S-some-set\".
  (add-set [_ token-set]
    (assert (token-set? token-set) "expected valid token-set")
    (let [path (get-set-prefixed-path token-set)]
      (TokensLib. (d/oassoc-in sets path token-set)
                  themes
                  active-themes)))

  (update-set [this id f]
    (assert (uuid? id) "expected uuid for `id`")
    (let [set (get-set this id)
          name (get-name set)
          prefixed-full-path (set-name->prefixed-full-path name)]
      (if set
        (let [set' (f set)
              name' (get-name set')
              prefixed-full-path' (get-set-prefixed-path set')]
          (if (= name name')
            (TokensLib. (d/oassoc-in sets prefixed-full-path set')
                        themes
                        active-themes)
            (TokensLib. (-> sets
                            (d/oassoc-in-before prefixed-full-path prefixed-full-path' set')
                            (d/dissoc-in prefixed-full-path))
                        (walk/postwalk
                         (fn [form]
                           (if (instance? TokenTheme form)
                             (update-set-name form name name')
                             form))
                         themes)
                        active-themes)))
        this)))

  (delete-set [this id]
    (assert (uuid? id) "expected uuid for `id`")
    (let [set           (get-set this id)
          set-name      (get-name set)
          prefixed-path (set-name->prefixed-full-path set-name)]
      (TokensLib. (d/dissoc-in sets prefixed-path)
                  (walk/postwalk
                   (fn [form]
                     (if (instance? TokenTheme form)
                       (disable-set form set-name)
                       form))
                   themes)
                  active-themes)))

  (move-set [_ from-path to-path before-path before-group?]
    (let [prefixed-from-path (set-full-path->set-prefixed-full-path from-path)
          prev-set (get-in sets prefixed-from-path)]
      (if (instance? TokenSet prev-set)
        (let [prefixed-to-path
              (set-full-path->set-prefixed-full-path to-path)

              prefixed-before-path
              (when before-path
                (if before-group?
                  (mapv add-set-path-group-prefix before-path)
                  (set-full-path->set-prefixed-full-path before-path)))

              set
              (rename prev-set (join-set-path to-path))

              reorder?
              (= prefixed-from-path prefixed-to-path)

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
                             (update-set-name form (get-name prev-set) (get-name set))
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
                                                        (if (token-set? form)
                                                          (rename form (str to-path-str (str/strip-prefix (get-name form) from-path-str)))
                                                          form))
                                                      sets)))))
              themes' (if reorder?
                        themes
                        (let [rename-sets-map (->> (get-sets-at-path this from-path)
                                                   (map (fn [set]
                                                          [(get-name set) (str to-path-str (str/strip-prefix (get-name set) from-path-str))]))
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

  (get-sets [_]
    (->> (tree-seq d/ordered-map? vals sets)
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
         (update-set lib (get-id set) (fn [set']
                                        (rename set' (str to-path-str (str/strip-prefix (get-name set') from-path-str))))))
       this sets)))

  (get-set-names [this]
    (map get-name (get-sets this)))

  (set-count [this]
    (count (get-sets this)))

  (get-set [this id]
    (assert (uuid? id) "expected uuid for `id`")
    (some #(when (= (get-id %) id) %)  ;; TODO: this will be made in an efficient way when
          (get-sets this)))            ;;       we refactor the tokens lib internal structure

  (get-set-by-name [_ name]
    (assert (string? name) "expected string for `name`")
    (let [path (set-name->prefixed-full-path name)]
      (get-in sets path)))

  ITokenThemes
  (add-theme [_ token-theme]
    (let [token-theme (check-token-theme token-theme)]
      (TokensLib. sets
                  (update themes (:group token-theme) d/oassoc (:name token-theme) token-theme)
                  active-themes)))

  (update-theme [this id f]
    (if-let [theme (get-theme this id)]
      (let [group       (:group theme)
            name        (:name theme)
            theme'      (-> (make-token-theme (f theme))
                            (assoc :modified-at (ct/now)))
            group'      (:group theme')
            name'       (:name theme')
            same-group? (= group group')
            same-name?  (= name name')
            same-path?  (and same-group? same-name?)]
        (TokensLib. sets
                    (if same-path?
                      (update themes group' assoc name' theme')
                      (-> themes
                          (d/oassoc-in-before [group name] [group' name'] theme')
                          (d/dissoc-in [group name])))
                    (if same-path?
                      active-themes
                      (disj active-themes (join-theme-path group name)))))
      this))


  (delete-theme [this id]
    (let [theme (get-theme this id)
          [group name] [(:group theme) (:name theme)]]
      (if theme
        (TokensLib. sets
                    (d/dissoc-in themes [group name])
                    (disj active-themes (join-theme-path group name)))
        this)))

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

  (get-theme [this id]
    (->> (get-themes this)
         (d/seek #(= (:id %) id))))

  (get-theme-by-name [_ group name]
    (dm/get-in themes [group name]))

  (set-active-themes [_ active-themes]
    (TokensLib. sets
                themes
                active-themes))

  (activate-theme [this id]
    (if-let [theme (get-theme this id)]
      (let [group (:group theme)
            group-themes (->> (get themes group)
                              (map (comp get-theme-path val))
                              (into #{}))
            active-themes' (-> (set/difference active-themes group-themes)
                               (conj (get-theme-path theme)))]
        (TokensLib. sets
                    themes
                    active-themes'))
      this))

  (deactivate-theme [this id]
    (if-let [theme (get-theme this id)]
      (TokensLib. sets
                  themes
                  (disj active-themes (get-theme-path theme)))
      this))

  (theme-active? [this id]
    (when-let [theme (get-theme this id)]
      (contains? active-themes (get-theme-path theme))))

  (toggle-theme-active? [this id]
    (if (theme-active? this id)
      (deactivate-theme this id)
      (activate-theme this id)))

  (get-active-theme-paths [_]
    active-themes)

  (get-active-themes [this]
    (into
     (list)
     (comp
      (filter (partial instance? TokenTheme))
      (filter #(theme-active? this (get-id %))))
     (tree-seq d/ordered-map? vals themes)))

  (get-hidden-theme [this]
    (get-theme this hidden-theme-id))

  ITokensLib
  (empty-lib? [this]
    (and (empty? sets)
         (or (empty? themes)
             (and (= (theme-count this) 1)
                  (get-hidden-theme this)))))

  (set-path-exists? [_ set-path]
    (some? (get-in sets (set-full-path->set-prefixed-full-path set-path))))

  (set-group-path-exists? [_ set-path]
    (some? (get-in sets (set-group-path->set-group-prefixed-path set-path))))

  (add-token [this set-id token]
    (update-set this set-id #(add-token- % token)))

  (get-token [this set-id token-id]
    (some-> this
            (get-set set-id)
            (get-token- token-id)))

  (get-token-by-name [this set-name token-name]
    (some-> this
            (get-set-by-name set-name)
            (get-token-by-name- token-name)))

  (update-token [this set-id token-id f]
    (update-set this set-id #(update-token- % token-id f)))

  (delete-token [this set-id token-id]
    (update-set this set-id #(delete-token- % token-id)))

  (toggle-set-in-theme [this theme-id set-name]
    (if-let [theme (get-theme this theme-id)]
      (TokensLib. sets
                  (d/oupdate-in themes [(:group theme) (:name theme)]
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
        (let [path-active-set-names (some->> (get-in sets (split-set-name prefixed-path-str))
                                             (tree-seq d/ordered-map? vals)
                                             (filter (partial instance? TokenSet))
                                             (map get-name)
                                             (into #{}))
              difference (set/difference path-active-set-names active-set-names)]
          (cond
            (empty? difference) :all
            (seq (set/intersection path-active-set-names active-set-names)) :partial
            :else :none))
        :none)))

  (get-tokens-in-active-sets [this]
    (let [theme-set-names  (get-active-themes-set-names this)
          all-set-names    (get-set-names this)
          active-set-names (filter theme-set-names all-set-names)
          tokens           (reduce (fn [tokens set-name]
                                     (let [set (get-set-by-name this set-name)]
                                       (merge tokens (get-tokens- set))))
                                   (d/ordered-map)
                                   active-set-names)]
      tokens))

  (get-all-tokens [this]
    (reduce
     (fn [tokens' set]
       (into tokens' (map (fn [x] [(:name x) x]) (vals (get-tokens- set)))))
     {}
     (get-sets this)))

  (get-tokens [this set-id]
    (some-> this
            (get-set set-id)
            (get-tokens-)))

  IValidation
  (valid? [this]
    (valid-tokens-lib-map? (datafy this)))

  (check [this]
    (check-tokens-lib-map (datafy this))
    this))

(defmethod pp/simple-dispatch TokensLib
  [^TokensLib obj]
  (.write *out* "#penpot/tokens-lib ")
  (pp/pprint-newline :miser)
  (pp/pprint (export-dtcg-json obj)))

#?(:clj
   (do
     (defmethod print-method TokensLib
       [^TokensLib obj ^java.io.Writer w]
       (.write w "#penpot/tokens-lib ")
       (print-method (export-dtcg-json obj) w))

     (defmethod print-dup TokensLib
       [^TokensLib obj ^java.io.Writer w]
       (print-method obj w)))

   :cljs
   (extend-type TokensLib
     cljs.core/IPrintWithWriter
     (-pr-writer [this writer opts]
       (-write writer "#penpot/tokens-lib ")
       (-pr-writer (export-dtcg-json this) writer opts))

     cljs.core/IEncodeJS
     (-clj->js [this] (clj->js (datafy this)))))

(def ^:private schema:tokens-lib-map
  "Internal data structure schema"
  [:map {:title "TokensLib"}
   [:sets schema:token-sets]
   [:themes schema:token-themes]
   [:active-themes schema:active-themes]])

(def ^:private valid-tokens-lib-map?
  (sm/lazy-validator schema:tokens-lib-map))

(def ^:private check-tokens-lib-map
  (sm/check-fn schema:tokens-lib-map :hint "invalid tokens-lib internal data structure"))

(defn valid-tokens-lib?
  [o]
  (and (instance? TokensLib o)
       (valid? o)))

(defn- ensure-hidden-theme
  "A helper that is responsible to ensure that the hidden theme always
  exists on the themes data structure"
  [themes]
  (update themes hidden-theme-group
          (fn [data]
            (if (contains? data hidden-theme-name)
              data
              (d/oassoc data hidden-theme-name (make-hidden-theme))))))

(defn map->tokens-lib
  "Make a new instance of TokensLib from a map, but skiping all
  validation; it is used for create new instances from trusted
  sources"
  [& {:keys [sets themes active-themes]}]
  (TokensLib. sets themes active-themes))

(defn make-tokens-lib
  "Make a new instance of TokensLib from a map and validates the input"
  [& {:as params}]
  (-> params
      (update :sets #(or % (d/ordered-map)))
      (update :themes #(-> (or % (d/ordered-map))
                           (ensure-hidden-theme)))
      (update :active-themes #(or % #{hidden-theme-path}))
      (check-tokens-lib-map)
      (map->tokens-lib)))

(defn ensure-tokens-lib
  [tokens-lib]
  (or tokens-lib (make-tokens-lib)))

(def schema:tokens-lib
  (sm/type-schema
   {:type ::tokens-lib
    :pred valid-tokens-lib?
    :type-properties
    ;; NOTE: we can't assign statically at eval time the value of a
    ;; function that is declared but not defined; so we need to pass
    ;; an anonymous function and delegate the resolution to runtime
    {:encode/json #(export-dtcg-json %)
     :decode/json #(parse-multi-set-dtcg-json %)}}))

(defn duplicate-set
  "Make a new set with a unique name, copying data from the given set in the lib."
  [set-id lib & {:keys [suffix]}]
  (let [sets      (get-sets lib)
        unames    (map get-name sets)
        set       (get-set lib set-id)
        copy-name (when set
                    (cfh/generate-unique-name (get-name set) unames :suffix suffix))]
    (when set
      (-> set
          (rename copy-name)
          (reid (uuid/next))))))

;; === Import / Export from JSON format

;; Supported formats:
;;  - Legacy: for tokens files prior to DTCG second draft
;;  - DTCG: for tokens files conforming to the DTCG second draft (current for now)
;;    https://www.w3.org/community/design-tokens/2022/06/14/call-to-implement-the-second-editors-draft-and-share-feedback/
;;
;;  - Single set: for files that comply with the base DTCG format, that contain a single tree of tokens.
;;  - Multi sets: for files with the Tokens Studio extension, that may contain several sets, and also themes and other $metadata.
;;
;; Small glossary:
;;  * json data: a json-encoded string
;;  * decode: convert a json string into a plain clojure nested map
;;  * parse: build a TokensLib (or a fragment) from a decoded json data
;;  * export: generate from a TokensLib a plain clojure nested map, suitable to be encoded as a json string

(def ^:private legacy-node?
  (sm/validator
   [:or
    [:map
     ["value" :string]
     ["type" :string]]
    [:map
     ["value" [:sequential [:map ["type" :string]]]]
     ["type" :string]]
    [:map
     ["value" :map]
     ["type" :string]]]))

(def ^:private dtcg-node?
  (sm/validator
   [:or
    [:map
     ["$value" :string]
     ["$type" :string]]
    [:map
     ["$value" [:sequential [:map ["$type" :string]]]]
     ["$type" :string]]
    [:map
     ["$value" :map]
     ["$type" :string]]]))

(defn- get-json-format
  "Searches through decoded token file and returns:
   - `:json-format/legacy` when first node satisfies `legacy-node?` predicate
   - `:json-format/dtcg` when first node satisfies `dtcg-node?` predicate
   - If neither combination is found, return dtcg format by default (we assume that
     the file does not contain any token, so the format is irrelevan)."
  ([decoded-json]
   (get-json-format decoded-json legacy-node? dtcg-node?))
  ([decoded-json legacy-node? dtcg-node?]
   (assert (map? decoded-json) "expected a plain clojure map for `decoded-json`")
   (let [branch? map?
         children (fn [node] (vals node))
         check-node (fn [node]
                      (cond
                        (legacy-node? node) :json-format/legacy
                        (dtcg-node? node) :json-format/dtcg
                        :else nil))
         walk (fn walk [node]
                (lazy-seq
                 (cons
                  (check-node node)
                  (when (branch? node)
                    (mapcat walk (children node))))))]
     (d/nilv (->> (walk decoded-json)
                  (filter some?)
                  first)
             :json-format/dtcg))))

(defn- legacy-json->dtcg-json
  "Converts a decoded json file in legacy format into DTCG format."
  [decoded-json]
  (assert (map? decoded-json) "expected a plain clojure map for `decoded-json`")
  (walk/postwalk
   (fn [node]
     (cond-> node
       ;; Handle sequential values that are objects with type
       (and (map? node)
            (contains? node "value")
            (sequential? (get node "value"))
            (map? (first (get node "value"))))
       (update "value"
               (fn [seq-value]
                 (map #(set/rename-keys % {"type" "$type"}) seq-value)))

       ;; Keep array of font families
       (and (map? node)
            (contains? node "type")
            (= "fontFamilies" (get node "type"))
            (contains? node "value")
            (sequential? (get node "value"))
            (not (map? (first (get node "value")))))
       identity

       ;; Rename keys for all token nodes
       (and (map? node)
            (and (contains? node "type")
                 (contains? node "value")))
       (set/rename-keys  {"value" "$value"
                          "type" "$type"})))
   decoded-json))

(defn- single-set?
  "Check if the decoded json file conforms to basic DTCG format with a single set."
  [decoded-json]
  (assert (map? decoded-json) "expected a plain clojure map for `decoded-json`")
  (and (not (contains? decoded-json "$metadata"))
       (not (contains? decoded-json "$themes"))))

(defn- convert-dtcg-font-family
  "Convert font-family token value from DTCG format to internal format.
   - If value is a string, split it into a collection of font families
   - If value is already an array, keep it as is
   - Otherwise keep as is"
  [value]
  (cond
    (string? value) (cto/split-font-family value)
    (sequential? value) value
    :else value))

(defn- convert-dtcg-typography-composite
  "Convert typography token value keys from DTCG format to internal format."
  [value]
  (if (map? value)
    (-> value
        (set/rename-keys cto/composite-dtcg-token-type->token-type)
        (select-keys cto/typography-keys)
        ;; Convert font-family values within typography composite tokens
        (d/update-when :font-family convert-dtcg-font-family))
    ;; Reference value
    value))

(defn- flatten-nested-tokens-json
  "Convert a tokens tree in the decoded json fragment into a flat map,
   being the keys the token paths after joining the keys with '.'."
  [decoded-json-tokens parent-path]
  (reduce-kv
   (fn [tokens k v]
     (let [child-path (if (empty? parent-path)
                        (name k)
                        (str parent-path "." k))]
       (if (and (map? v)
                (not (contains? v "$type")))
         (merge tokens (flatten-nested-tokens-json v child-path))
         (let [token-type (cto/dtcg-token-type->token-type (get v "$type"))]
           (if token-type
             (assoc tokens child-path (make-token
                                       :name child-path
                                       :type token-type
                                       :value
                                       (let [token-value (get v "$value")]
                                         (case token-type
                                           :font-family (convert-dtcg-font-family token-value)
                                           :typography (convert-dtcg-typography-composite token-value)
                                           token-value))
                                       :description (get v "$description")))
             ;; Discard unknown type tokens
             tokens)))))
   {}
   decoded-json-tokens))

(defn- parse-single-set-dtcg-json
  "Parse a decoded json file with a single set of tokens in DTCG format into a TokensLib."
  [set-name decoded-json-tokens]
  (assert (map? decoded-json-tokens) "expected a plain clojure map for `decoded-json-tokens`")
  (assert (= (get-json-format decoded-json-tokens) :json-format/dtcg) "expected a dtcg format for `decoded-json-tokens`")

  (let [set-name (normalize-set-name set-name)
        tokens   (flatten-nested-tokens-json decoded-json-tokens "")]

    (when (empty? tokens)
      (throw (ex-info "the file doesn't contain any tokens"
                      {:error/code :error.import/invalid-json-data})))

    (-> (make-tokens-lib)
        (add-set (make-token-set :name set-name
                                 :tokens tokens)))))

(defn- parse-single-set-legacy-json
  "Parse a decoded json file with a single set of tokens in legacy format into a TokensLib."
  [set-name decoded-json-tokens]
  (assert (map? decoded-json-tokens) "expected a plain clojure map for `decoded-json-tokens`")
  (assert (= (get-json-format decoded-json-tokens) :json-format/legacy) "expected a legacy format for `decoded-json-tokens`")
  (parse-single-set-dtcg-json set-name (legacy-json->dtcg-json decoded-json-tokens)))

(defn parse-multi-set-dtcg-json
  "Parse a decoded json file with multi sets in DTCG format into a TokensLib."
  [decoded-json]
  (assert (map? decoded-json) "expected a plain clojure map for `decoded-json`")
  (assert (= (get-json-format decoded-json) :json-format/dtcg) "expected a dtcg format for `decoded-json`")

  (let [metadata (get decoded-json "$metadata")

        xf-normalize-set-name
        (map normalize-set-name)

        sets
        (dissoc decoded-json "$themes" "$metadata")

        ordered-set-names
        (-> (d/ordered-set)
            (into xf-normalize-set-name (get metadata "tokenSetOrder"))
            (into xf-normalize-set-name (keys sets)))

        active-set-names
        (or (->> (get metadata "activeSets")
                 (into #{} xf-normalize-set-name)
                 (not-empty))
            #{})

        active-theme-names
        (or (->> (get metadata "activeThemes")
                 (into #{})
                 (not-empty))
            #{hidden-theme-path})

        themes
        (->> (get decoded-json "$themes")
             (map (fn [theme]
                    (make-token-theme
                     :id (or (uuid/parse* (get theme "id"))
                             (uuid/next))
                     :name (get theme "name")
                     :group (get theme "group")
                     :is-source (get theme "is-source")
                     :external-id (get theme "id")
                     :modified-at (some-> (get theme "modified-at")
                                          (ct/inst))
                     :sets (into #{}
                                 (comp (map key)
                                       xf-normalize-set-name
                                       (filter #(contains? ordered-set-names %)))
                                 (get theme "selectedTokenSets")))))
             (not-empty))

        library
        (make-tokens-lib)

        sets
        (reduce-kv (fn [result name tokens]
                     (assoc result
                            (normalize-set-name name)
                            (flatten-nested-tokens-json tokens "")))
                   {}
                   sets)

        library
        (reduce (fn [library name]
                  (if-let [tokens (get sets name)]
                    (add-set library (make-token-set :name name :tokens tokens))
                    library))
                library
                ordered-set-names)

        library
        (update-theme library hidden-theme-id
                      #(assoc % :sets active-set-names))

        library
        (reduce add-theme library themes)

        library
        (reduce (fn [library theme-path]
                  (let [[group name] (split-theme-path theme-path)
                        theme        (get-theme-by-name library group name)]
                    (activate-theme library (get-id theme))))
                library
                active-theme-names)]

    (when (and (empty? sets) (empty? themes))
      (throw (ex-info "the file doesn't contain any tokens"
                      {:error/code :error.import/invalid-json-data})))

    library))

(defn- parse-multi-set-legacy-json
  "Parse a decoded json file with multi sets in legacy format into a TokensLib."
  [decoded-json]
  (assert (map? decoded-json) "expected a plain clojure map for `decoded-json`")
  (assert (= (get-json-format decoded-json) :json-format/legacy) "expected a legacy format for `decoded-json`")

  (let [sets-data      (dissoc decoded-json "$themes" "$metadata")
        other-data     (select-keys decoded-json ["$themes" "$metadata"])
        dtcg-sets-data (legacy-json->dtcg-json sets-data)]
    (parse-multi-set-dtcg-json (merge other-data
                                      dtcg-sets-data))))

(defn parse-decoded-json
  "Guess the format and content type of the decoded json file and parse it into a TokensLib.
   The `file-name` is used to determine the set name when the json file contains a single set."
  [decoded-json file-name]
  (let [single-set? (single-set? decoded-json)
        json-format (get-json-format decoded-json)]
    (cond
      (and single-set?
           (= :json-format/legacy json-format))
      (parse-single-set-legacy-json file-name decoded-json)

      (and single-set?
           (= :json-format/dtcg json-format))
      (parse-single-set-dtcg-json file-name decoded-json)

      (= :json-format/legacy json-format)
      (parse-multi-set-legacy-json decoded-json)

      :else
      (parse-multi-set-dtcg-json decoded-json))))

(defn- typography-token->dtcg-token
  [value]
  (if (map? value)
    (reduce-kv
     (fn [acc k v]
       (if (contains? cto/typography-keys k)
         (assoc acc (cto/composite-token-type->dtcg-token-type k) v)
         acc))
     {} value)
    value))

(defn- token->dtcg-token [token]
  (cond-> {"$value" (cond-> (:value token)
                      ;; Transform typography token values
                      (= :typography (:type token))
                      typography-token->dtcg-token)
           "$type" (cto/token-type->dtcg-token-type (:type token))}
    (:description token) (assoc "$description" (:description token))))

(defn- dtcg-export-themes
  "Extract themes for a dtcg json export."
  [tokens-lib]
  (let [themes-xform
        (comp
         (filter #(and (instance? TokenTheme %)
                       (not (hidden-theme? %))))
         (map (fn [token-theme]
                (let [theme-map (->> token-theme
                                     (into {})
                                     walk/stringify-keys)]
                  (-> theme-map
                      (set/rename-keys  {"sets" "selectedTokenSets"
                                         "external-id" "id"})
                      (update "selectedTokenSets" (fn [sets]
                                                    (->> (for [s sets] [s "enabled"])
                                                         (into {})))))))))
        themes
        (->> (get-theme-tree tokens-lib)
             (tree-seq d/ordered-map? vals)
             (into [] themes-xform))

        ;; Active themes without exposing hidden penpot theme
        active-themes
        (-> (get-active-theme-paths tokens-lib)
            (disj hidden-theme-path))]
    {:themes themes
     :active-themes active-themes}))

(defn export-dtcg-multi-file
  "Convert a TokensLib into a plain clojure map, suitable to be encoded as a multi json files each encoded in DTCG format."
  [tokens-lib]
  (let [{:keys [themes active-themes]} (dtcg-export-themes tokens-lib)
        sets (->> (get-sets tokens-lib)
                  (map (fn [token-set]
                         (let [name   (get-name token-set)
                               tokens (get-tokens- token-set)]
                           [(str name ".json") (tokens-tree tokens :update-token-fn token->dtcg-token)])))
                  (into {}))]
    (-> sets
        (assoc "$themes.json" themes)
        (assoc "$metadata.json" {"tokenSetOrder" (get-set-names tokens-lib)
                                 "activeThemes" active-themes
                                 "activeSets" (get-active-themes-set-names tokens-lib)}))))

(defn export-dtcg-json
  "Convert a TokensLib into a plain clojure map, suitable to be encoded as a multi sets json string in DTCG format."
  [tokens-lib]
  (let [{:keys [themes active-themes]} (dtcg-export-themes tokens-lib)

        name-set-tuples
        (->> (get-set-tree tokens-lib)
             (tree-seq d/ordered-map? vals)
             (filter (partial instance? TokenSet))
             (map (fn [set]
                    [(get-name set)
                     (tokens-tree (get-tokens- set) :update-token-fn token->dtcg-token)])))

        ordered-set-names
        (mapv first name-set-tuples)

        sets
        (into {} name-set-tuples)

        active-set-names
        (get-active-themes-set-names tokens-lib)]

    (when-not (empty-lib? tokens-lib)
      (-> sets
          (assoc "$themes" themes)
          (assoc "$metadata" {"tokenSetOrder" ordered-set-names
                              "activeThemes" active-themes
                              "activeSets" active-set-names})))))

(defn get-tokens-of-unknown-type
  "Search for all tokens in the decoded json file that have a type that is not currently
   supported by Penpot. Returns a map token-path -> token type."
  [decoded-json {:keys [json-format parent-path process-token-type]
                 :or {json-format (get-json-format decoded-json)
                      parent-path ""
                      process-token-type identity}
                 :as opts}]
  (let [type-key (if (= json-format :json-format/dtcg) "$type" "type")]
    (reduce-kv
     (fn [unknown-tokens k v]
       (let [child-path (if (empty? parent-path)
                          (name k)
                          (str parent-path "." k))]
         (if (and (map? v)
                  (not (contains? v type-key)))
           (let [nested-unknown-tokens (get-tokens-of-unknown-type v (assoc opts :parent-path child-path))]
             (merge unknown-tokens nested-unknown-tokens))
           (let [token-type-str (get v type-key)
                 token-type (-> (cto/dtcg-token-type->token-type token-type-str)
                                (process-token-type))]
             (if (and (not (some? token-type)) (some? token-type-str))
               (assoc unknown-tokens child-path token-type-str)
               unknown-tokens)))))
     nil
     decoded-json)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SERIALIZATION (TRANSIT)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Serialization used for communicate data in transit between backend
;; and the frontend

(t/add-handlers!
 {:id "penpot/tokens-lib"
  :class TokensLib
  :wfn datafy
  :rfn #(make-tokens-lib %)}

 {:id "penpot/token-set"
  :class TokenSet
  :wfn datafy
  :rfn #(make-token-set %)}

 {:id "penpot/token-theme"
  :class TokenTheme
  :wfn datafy
  :rfn #(map->TokenTheme %)}

 {:id "penpot/token"
  :class Token
  :wfn datafy
  :rfn #(map->Token %)})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; MIGRATIONS HELPERS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn fix-duplicate-token-set-ids
  "Given an instance of TokensLib fixes it internal sets data sturcture
  for ensure each set has unique id;

  Specific function for file data migrations"
  [tokens-lib]
  (let [seen-ids
        (volatile! #{})

        migrate-set-node
        (fn recurse [node]
          (if (token-set? node)
            (if (contains? @seen-ids (get-id node))
              (-> (datafy node)
                  (assoc :id (uuid/next))
                  (map->token-set))
              (do
                (vswap! seen-ids conj (get-id node))
                node))
            (d/update-vals node recurse)))]

    (some-> tokens-lib
            (-> (datafy)
                (update :sets d/update-vals migrate-set-node)
                (map->tokens-lib)
                (check)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SERIALIZATION (FRESIAN)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Serialization used for the internal storage on the file data, it
;; uses and, space and cpu efficient fresian serialization.

#?(:clj
   (defn- migrate-to-v1-2
     "Migrate the TokensLib data structure internals to v1.2 version; it
     expects input from v1.1 version"
     [{:keys [themes] :as params}]

     (let [;; Ensure we have at least a hidden theme
           themes
           (ensure-hidden-theme themes)

           ;; Ensure we add an :id field for each existing theme
           themes
           (reduce (fn [acc group-id]
                     (update acc group-id
                             (fn [themes]
                               (reduce (fn [themes theme-id]
                                         (update themes theme-id
                                                 (fn [theme]
                                                   (if (get theme :id)
                                                     theme
                                                     (assoc theme :id (str (uuid/next)))))))
                                       themes
                                       (keys themes)))))
                   themes
                   (keys themes))]
       (assoc params :themes themes))))

#?(:clj
   (defn- migrate-to-v1-3
     "Migrate the TokensLib data structure internals to v1.3 version; it
  expects input from v1.2 version"
     [{:keys [sets themes] :as params}]
     (let [migrate-token
           (fn [token]
             (assoc token :id (uuid/next)))

           migrate-set-node
           (fn recurse [node]
             (if (token-set-legacy? node)
               (make-token-set
                (assoc node
                       :id (uuid/next)
                       :tokens (d/update-vals (:tokens node) migrate-token)))
               (d/update-vals node recurse)))

           sets
           (d/update-vals sets migrate-set-node)

           migrate-theme
           (fn [theme]
             (if (get theme :external-id)
               theme
               (if (hidden-theme? theme)
                 (assoc theme
                        :id uuid/zero
                        :external-id "")
                 ;; Rename the :id field to :external-id, and add a
                 ;; new :id that is the same as the old if if this is an
                 ;; uuid, else a new uuid is generated.
                 (assoc theme
                        :id (or (uuid/parse* (:id theme))
                                (uuid/next))
                        :external-id (:id theme)))))

           migrate-theme-group
           (fn [group]
             (d/update-vals group migrate-theme))

           themes
           (d/update-vals themes migrate-theme-group)]

       (assoc params
              :themes themes
              :sets sets))))

#?(:clj
   (defn- migrate-to-v1-4
     "Migrate the TokensLib data structure internals to v1.4 version; it
  expects input from v1.3 version"
     [params]
     (let [migrate-set-node
           (fn recurse [node]
             (cond
               (token-set-legacy? node)
               (make-token-set node)

               (token-set? node)
               node

               :else
               (d/update-vals node recurse)))]

       (update params :sets d/update-vals migrate-set-node))))

#?(:clj
   (defn- read-tokens-lib-v1-1
     "Reads the tokens lib data structure and ensures that hidden
     theme exists and adds missing ID on themes"
     [r]
     (let [sets          (fres/read-object! r)
           themes        (fres/read-object! r)
           active-themes (fres/read-object! r)]

       (-> {:sets sets
            :themes themes
            :active-themes active-themes}
           (migrate-to-v1-2)
           (migrate-to-v1-3)
           (migrate-to-v1-4)
           (map->tokens-lib)))))

#?(:clj
   (defn- read-tokens-lib-v1-2
     "Reads the tokens lib data structure and add ids to tokens, sets and themes."
     [r]
     (let [sets          (fres/read-object! r)
           themes        (fres/read-object! r)
           active-themes (fres/read-object! r)]

       (-> {:sets sets
            :themes themes
            :active-themes active-themes}
           (migrate-to-v1-3)
           (migrate-to-v1-4)
           (map->tokens-lib)))))

#?(:clj
   (defn- read-tokens-lib-v1-3
     "Reads the tokens lib data structure and removes the TokenSetLegacy data type,
      needed for a temporary migration step."
     [r]
     (let [sets          (fres/read-object! r)
           themes        (fres/read-object! r)
           active-themes (fres/read-object! r)]

       (-> {:sets sets
            :themes themes
            :active-themes active-themes}
           (migrate-to-v1-4)
           (map->tokens-lib)))))

#?(:clj
   (defn- write-tokens-lib
     [n w ^TokensLib o]
     (fres/write-tag! w n 3)
     (fres/write-object! w (.-sets o))
     (fres/write-object! w (.-themes o))
     (fres/write-object! w (.-active-themes o))))

#?(:clj
   (defn- read-tokens-lib
     [r]
     (let [sets          (fres/read-object! r)
           themes        (fres/read-object! r)
           active-themes (fres/read-object! r)]
       (->TokensLib sets themes active-themes))))

#?(:clj
   (fres/add-handlers!
    {:name "penpot/token/v1"
     :class Token
     :wfn (fn [n w o]
            (fres/write-tag! w n 1)
            (fres/write-object! w (into {} o)))
     :rfn (fn [r]
            (let [obj (fres/read-object! r)]
              (make-token obj)))}

    {:name "penpot/token-set/v1"
     :rfn (fn [r]
            (let [obj (fres/read-object! r)]
              (map->TokenSetLegacy obj)))}

    {:name "penpot/token-set/v2"
     :class TokenSet
     :wfn (fn [n w o]
            (fres/write-tag! w n 1)
            (fres/write-object! w (datafy o)))
     :rfn (fn [r]
            (let [obj (fres/read-object! r)]
              (make-token-set obj)))}

    {:name "penpot/token-theme/v1"
     :class TokenTheme
     :wfn (fn [n w o]
            (fres/write-tag! w n 1)
            (fres/write-object! w (into {} o)))
     :rfn (fn [r]
            (let [obj (fres/read-object! r)]
              (make-token-theme obj)))}

    {:name "penpot/tokens-lib/v1.1"
     :rfn read-tokens-lib-v1-1}

    {:name "penpot/tokens-lib/v1.2"
     :rfn read-tokens-lib-v1-2}

    {:name "penpot/tokens-lib/v1.3"
     :rfn read-tokens-lib-v1-3}

    ;; CURRENT TOKENS LIB READER & WRITTER
    {:name "penpot/tokens-lib/v1.4"
     :class TokensLib
     :wfn write-tokens-lib
     :rfn read-tokens-lib}))
