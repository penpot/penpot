;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.common.types.tokens-lib
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.schema :as sm]
   [app.common.time :as dt]
   [app.common.transit :as t]
   [app.common.types.token :as cto]
   [cuerdas.core :as str]
   #?(:clj [app.common.fressian :as fres])))

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
  "Get the groups part of the name as a vector. E.g. group.subgroup.name -> ['group' 'subrgoup']"
  [item separator]
  (dm/assert!
   "expected groupable item"
   (valid-groupable-item? item))
  (split-path (:name item) separator))

(defn get-groups-str
  "Get the groups part of the name. E.g. group.subgroup.name -> group.subrgoup"
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

(defrecord Token [name type value description modified-at])

(def schema:token
  [:and
   [:map {:title "Token"}
    [:name cto/token-name-ref]            ;; not necessary to have uuid
    [:type [::sm/one-of cto/token-types]]
    [:value :any]
    [:description [:maybe :string]]       ;; defrecord always have the attributes, even with nil value
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

;; === Token Set

(defprotocol ITokenSet
  (add-token [_ token] "add a token at the end of the list")
  (update-token [_ token-name f] "update a token in the list")
  (delete-token [_ token-name] "delete a token from the list")
  (get-tokens [_] "return an ordered sequence of all tokens in the set"))

(defrecord TokenSet [name description modified-at tokens]
  ITokenSet
  (add-token [_ token]
    (dm/assert! "expected valid token" (check-token! token))
    (let [path (split-path (:name token) ".")]
      (TokenSet. name
                 description
                 (dt/now)
                 (d/oassoc-in tokens path token))))

  (update-token [this token-name f]
    (let [path (split-path token-name ".")
          token (get-in tokens path)]
      (if token
        (let [token' (-> (make-token (f token))
                         (assoc :modified-at (dt/now)))
              path'  (get-path token' ".")]
          (check-token! token')
          (TokenSet. name
                     description
                     (dt/now)
                     (if (= (:name token) (:name token'))
                       (d/oassoc-in tokens path token')
                       (-> tokens
                           (d/oassoc-in-before path path' token')
                           (d/dissoc-in path)))))
        this)))

  (delete-token [_ token-name]
    (let [path (split-path token-name ".")]
      (TokenSet. name
                 description
                 (dt/now)
                 (d/dissoc-in tokens path))))

  (get-tokens [_]
    (->> (tree-seq d/ordered-map? vals tokens)
         (filter (partial instance? Token)))))

(def schema:token-node
  [:schema {:registry {::node [:or ::token
                               [:and
                                [:map-of {:gen/max 5} :string [:ref ::node]]
                                [:fn d/ordered-map?]]]}}
   [:ref ::node]])

(sm/register! ::token-node schema:token-node)

(def schema:token-set
  [:and [:map {:title "TokenSet"}
         [:name :string]
         [:description [:maybe :string]]
         [:modified-at ::sm/inst]
         [:tokens [:and [:map-of {:gen/max 5} :string ::token-node]
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

;; === TokenSetGroup

(defrecord TokenSetGroup [attr1 attr2])

;; TODO schema, validators, etc.

(defn make-token-set-group
  []
  (TokenSetGroup. "one" "two"))

;; === TokenSets (collection)

(defprotocol ITokenSets
  (add-set [_ token-set] "add a set to the library, at the end")
  (update-set [_ set-name f] "modify a set in the ilbrary")
  (delete-set [_ set-name] "delete a set in the library")
  (set-count [_] "get the total number if sets in the library")
  (get-set-tree [_] "get a nested tree of all sets in the library")
  (get-sets [_] "get an ordered sequence of all sets in the library")
  (get-set [_ set-name] "get one set looking for name")
  (get-set-group [_ set-group-path] "get the attributes of a set group"))

(def schema:token-sets
  [:and
   [:map-of {:title "TokenSets"}
    :string ::token-set]
   [:fn d/ordered-map?]])

(sm/register! ::token-sets schema:token-sets)

(def valid-token-sets?
  (sm/validator schema:token-sets))

(def check-token-sets!
  (sm/check-fn ::token-sets))

;; === TokenTheme

(defprotocol ITokenTheme
  (toggle-set [_ set-name] "togle a set used / not used in the theme"))

(defrecord TokenTheme [name group description is-source modified-at sets]
  ITokenTheme
  (toggle-set [_ set-name]
    (TokenTheme. name
                 group
                 description
                 is-source
                 (dt/now)
                 (if (sets set-name)
                   (disj sets set-name)
                   (conj sets set-name)))))

(def schema:token-theme
  [:and [:map {:title "TokenTheme"}
         [:name :string]
         [:group :string]
         [:description [:maybe :string]]
         [:is-source :boolean]
         [:modified-at ::sm/inst]
         [:sets [:and [:set {:gen/max 5} :string]
                 [:fn d/ordered-set?]]]]
   [:fn (partial instance? TokenTheme)]])

(sm/register! ::token-theme schema:token-theme)

(def valid-token-theme?
  (sm/validator schema:token-theme))

(def check-token-theme!
  (sm/check-fn ::token-theme))

(defn make-token-theme
  [& {:keys [] :as params}]
  (let [params    (-> params
                      (dissoc :id)
                      (update :group #(or % ""))
                      (update :is-source #(or % false))
                      (update :modified-at #(or % (dt/now)))
                      (update :sets #(into (d/ordered-set) %)))
        token-theme (map->TokenTheme params)]

    (dm/assert!
     "expected valid token theme"
     (check-token-theme! token-theme))

    token-theme))

;; === TokenThemes (collection)

(defprotocol ITokenThemes
  (add-theme [_ token-theme] "add a theme to the library, at the end")
  (update-theme [_ group name f] "modify a theme in the ilbrary")
  (delete-theme [_ group name] "delete a theme in the library")
  (theme-count [_] "get the total number if themes in the library")
  (get-theme-tree [_] "get a nested tree of all themes in the library")
  (get-themes [_] "get an ordered sequence of all themes in the library")
  (get-theme [_ group name] "get one theme looking for name"))

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

;; === Tokens Lib

(defprotocol ITokensLib
  "A library of tokens, sets and themes."
  (add-token-in-set [_ set-name token] "add token to a set")
  (update-token-in-set [_ set-name token-name f] "update a token in a set")
  (delete-token-from-set [_ set-name token-name] "delete a token from a set")
  (toggle-set-in-theme [_ group-name theme-name set-name] "toggle a set used / not used in a theme")
  (validate [_]))

(deftype TokensLib [sets set-groups themes]
  ;; NOTE: This is only for debug purposes, pending to properly
  ;; implement the toString and alternative printing.
  #?@(:clj  [clojure.lang.IDeref
             (deref [_] {:sets sets :set-groups set-groups :themes themes})]
      :cljs [cljs.core/IDeref
             (-deref [_] {:sets sets :set-groups set-groups :themes themes})])

  #?@(:cljs [cljs.core/IEncodeJS
             (-clj->js [_] (js-obj "sets" (clj->js sets)
                                   "set-groups" (clj->js set-groups)
                                   "themes" (clj->js themes)))])

  ITokenSets
  (add-set [_ token-set]
    (dm/assert! "expected valid token set" (check-token-set! token-set))
    (let [path       (get-path token-set "/")
          groups-str (get-groups-str token-set "/")]
      (TokensLib. (d/oassoc-in sets path token-set)
                  (cond-> set-groups
                    (not (str/empty? groups-str))
                    (assoc groups-str (make-token-set-group)))
                  themes)))

  (update-set [this set-name f]
    (let [path (split-path set-name "/")
          set  (get-in sets path)]
      (if set
        (let [set'  (-> (make-token-set (f set))
                        (assoc :modified-at (dt/now)))
              path' (get-path set' "/")]
          (check-token-set! set')
          (TokensLib. (if (= (:name set) (:name set'))
                        (d/oassoc-in sets path set')
                        (-> sets
                            (d/oassoc-in-before path path' set')
                            (d/dissoc-in path)))
                      set-groups  ;; TODO update set-groups as needed
                      themes))
        this)))

  (delete-set [_ set-name]
    (let [path (split-path set-name "/")]
      (TokensLib. (d/dissoc-in sets path)
                  set-groups  ;; TODO remove set-group if needed
                  themes)))

  (get-set-tree [_]
    sets)

  (get-sets [_]
    (->> (tree-seq d/ordered-map? vals sets)
         (filter (partial instance? TokenSet))))

  (set-count [this]
    (count (get-sets this)))

  (get-set [_ set-name]
    (let [path (split-path set-name "/")]
      (get-in sets path)))

  (get-set-group [_ set-group-path]
    (get set-groups set-group-path))

  ITokenThemes
  (add-theme [_ token-theme]
    (dm/assert! "expected valid token theme" (check-token-theme! token-theme))
    (TokensLib. sets
                set-groups
                (update themes (:group token-theme) d/oassoc (:name token-theme) token-theme)))

  (update-theme [this group name f]
    (let [theme (dm/get-in themes [group name])]
      (if theme
        (let [theme' (-> (make-token-theme (f theme))
                         (assoc :modified-at (dt/now)))
              group' (:group theme')
              name'  (:name theme')]
          (check-token-theme! theme')
          (TokensLib. sets
                      set-groups
                      (if (and (= group group') (= name name'))
                        (update themes group' assoc name' theme')
                        (-> themes
                            (d/oassoc-in-before [group name] [group' name'] theme')
                            (d/dissoc-in [group name])))))
        this)))

  (delete-theme [_ group name]
    (TokensLib. sets
                set-groups
                (d/dissoc-in themes [group name])))

  (get-theme-tree [_]
    themes)

  (get-themes [_]
    (->> (tree-seq d/ordered-map? vals themes)
         (filter (partial instance? TokenTheme))))

  (theme-count [this]
    (count (get-themes this)))

  (get-theme [_ group name]
    (dm/get-in themes [group name]))

  ITokensLib
  (add-token-in-set [this set-name token]
    (dm/assert! "expected valid token instance" (check-token! token))
    (if (contains? sets set-name)
      (TokensLib. (update sets set-name add-token token)
                  set-groups
                  themes)
      this))

  (update-token-in-set [this set-name token-name f]
    (if (contains? sets set-name)
      (TokensLib. (update sets set-name
                          #(update-token % token-name f))
                  set-groups
                  themes)
      this))

  (delete-token-from-set [this set-name token-name]
    (if (contains? sets set-name)
      (TokensLib. (update sets set-name
                          #(delete-token % token-name))
                  set-groups
                  themes)
      this))

  (toggle-set-in-theme [this theme-group theme-name set-name]
    (if-let [_theme (get-in themes theme-group theme-name)]
      (TokensLib. sets
                  set-groups
                  (d/oupdate-in themes [theme-group theme-name]
                                #(toggle-set % set-name)))
      this))

  (validate [_]
    (and (valid-token-sets? sets)  ;; TODO: validate set-groups
         (valid-token-themes? themes))))

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
                    :set-groups {}
                    :themes (d/ordered-map)))

  ([& {:keys [sets set-groups themes]}]
   (let [tokens-lib (TokensLib. sets set-groups themes)]

     (dm/assert!
      "expected valid tokens lib"
      (valid-tokens-lib? tokens-lib))

     tokens-lib)))

(defn ensure-tokens-lib
  [tokens-lib]
  (or tokens-lib (make-tokens-lib)))

(def type:tokens-lib
  {:type ::tokens-lib
   :pred valid-tokens-lib?})

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
     :class TokensLib
     :wfn (fn [n w o]
            (fres/write-tag! w n 3)
            (fres/write-object! w (.-sets o))
            (fres/write-object! w (.-set-groups o))
            (fres/write-object! w (.-themes o)))
     :rfn (fn [r]
            (let [sets       (fres/read-object! r)
                  set-groups (fres/read-object! r)
                  themes     (fres/read-object! r)]
              (->TokensLib sets set-groups themes)))}))
