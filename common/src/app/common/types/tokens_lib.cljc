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

;; === Token

(defrecord Token [name type value description modified-at])

(def schema:token
  [:and
   [:map {:title "Token"}
    [:name cto/token-name-ref]                            ;; not necessary to have uuid
    [:type [::sm/one-of cto/token-types]]
    [:value :any]
    [:description [:maybe :string]]      ;; defrecord always have the attributes, even with nil value
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
                     (let [index (d/index-of (keys tokens) (:name token))]
                       (-> tokens
                           (dissoc (:name token))
                           (d/addm-at-index index (:name token') token'))))))
      this))

  (delete-token [_ token-name]
    (TokenSet. name
               description
               (dt/now)
               (dissoc tokens token-name)))

  (get-tokens [_]
    (vals tokens)))

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
  (add-set [_ token-set] "add a set to the library, at the end")
  (update-set [_ set-name f] "modify a set in the ilbrary")
  (delete-set [_ set-name] "delete a set in the library")
  (set-count [_] "get the total number if sets in the library")
  (get-sets [_] "get an ordered sequence of all sets in the library")
  (get-set [_ set-name] "get one set looking for name"))

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

(defrecord TokenTheme [name description is-source modified-at sets]
  ITokenTheme
  (toggle-set [_ set-name]
    (TokenTheme. name
                 description
                 is-source
                 (dt/now)
                 (if (sets set-name)
                   (disj sets set-name)
                   (conj sets set-name)))))

(def schema:token-theme
  [:and [:map {:title "TokenTheme"}
         [:name :string]
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
                      (dissoc :group)
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
  (update-theme [_ theme-name f] "modify a theme in the ilbrary")
  (delete-theme [_ theme-name] "delete a theme in the library")
  (theme-count [_] "get the total number if themes in the library")
  (get-themes [_] "get an ordered sequence of all themes in the library")
  (get-theme [_ theme-name] "get one theme looking for name"))

(def schema:token-themes
  [:and
   [:map-of {:title "TokenThemes"}
    :string ::token-theme]
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
  (toggle-set-in-theme [_ theme-name set-name] "toggle a set used / not used in a theme")
  (validate [_]))

(deftype TokensLib [sets themes]
  ;; NOTE: This is only for debug purposes, pending to properly
  ;; implement the toString and alternative printing.
  #?@(:clj  [clojure.lang.IDeref
             (deref [_] {:sets sets :themes themes})]
      :cljs [cljs.core/IDeref
             (-deref [_] {:sets sets :themes themes})])

  #?@(:cljs [cljs.core/IEncodeJS
             (-clj->js [_] (js-obj "sets" (clj->js sets)
                                   "themes" (clj->js themes)))])

  ITokenSets
  (add-set [_ token-set]
    (dm/assert! "expected valid token set" (check-token-set! token-set))
    (TokensLib. (assoc sets (:name token-set) token-set)
                themes))

  (update-set [this set-name f]
    (if-let [set (get sets set-name)]
      (let [set' (-> (make-token-set (f set))
                     (assoc :modified-at (dt/now)))]
        (check-token-set! set')
        (TokensLib. (if (= (:name set) (:name set'))
                      (assoc sets (:name set') set')
                      (let [index (d/index-of (keys sets) (:name set))]
                        (-> sets
                            (dissoc (:name set))
                            (d/addm-at-index index (:name set') set'))))
                    themes))
      this))
  
  (delete-set [_ set-name]
    (TokensLib. (dissoc sets set-name)
                themes))

  (set-count [_]
    (count sets))

  (get-sets [_]
    (vals sets))

  (get-set [_ set-name]
    (get sets set-name))

  ITokenThemes
  (add-theme [_ token-theme]
    (dm/assert! "expected valid token theme" (check-token-theme! token-theme))
    (TokensLib. sets
                (assoc themes (:name token-theme) token-theme)))

  (update-theme [this theme-name f]
    (if-let [theme (get themes theme-name)]
      (let [theme' (-> (make-token-theme (f theme))
                       (assoc :modified-at (dt/now)))]
        (check-token-theme! theme')
        (TokensLib. sets
                    (if (= (:name theme) (:name theme'))
                      (assoc themes (:name theme') theme')
                      (let [index (d/index-of (keys themes) (:name theme))]
                        (-> themes
                            (dissoc (:name theme))
                            (d/addm-at-index index (:name theme') theme'))))))
      this))

  (delete-theme [_ theme-name]
    (TokensLib. sets
                (dissoc themes theme-name)))

  (theme-count [_]
    (count themes))

  (get-themes [_]
    (vals themes))

  (get-theme [_ theme-name]
    (get themes theme-name))

  ITokensLib
  (add-token-in-set [this set-name token]
    (dm/assert! "expected valid token instance" (check-token! token))
    (if (contains? sets set-name)
      (TokensLib. (update sets set-name add-token token)
                  themes)
      this))

  (update-token-in-set [this set-name token-name f]
    (if (contains? sets set-name)
      (TokensLib. (update sets set-name
                          #(update-token % token-name f))
                  themes)
      this))

  (delete-token-from-set [this set-name token-name]
    (if (contains? sets set-name)
      (TokensLib. (update sets set-name
                          #(delete-token % token-name))
                  themes)
      this))

  (toggle-set-in-theme [this theme-name set-name]
    (if (contains? themes theme-name)
      (TokensLib. sets
                  (update themes theme-name
                          #(toggle-set % set-name)))
      this))

  (validate [_]
    (and (valid-token-sets? sets)
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
                    :themes (d/ordered-map)))

  ([& {:keys [sets themes]}]
   (let [tokens-lib (TokensLib. sets themes)]

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
            (fres/write-tag! w n 2)
            (fres/write-object! w (.-sets o))
            (fres/write-object! w (.-themes o)))
     :rfn (fn [r]
            (let [sets   (fres/read-object! r)
                  themes (fres/read-object! r)]
              (->TokensLib sets themes)))}))

;; === Groups handling

(def schema:groupable-item
  [:map {:title "Groupable item"}
   [:name :string]])

(def valid-groupable-item?
  (sm/validator schema:groupable-item))

(defn split-path
  "Decompose a string in the form 'one.two.three' into a vector of strings, removing spaces."
  [path]
  (let [xf (comp (map str/trim)
                 (remove str/empty?))]
    (->> (str/split path ".")
         (into [] xf))))

(defn join-path
  "Regenerate a path as a string, from a vector."
  [path-vec]
  (str/join "." path-vec))

(defn group-item
  "Add a group to the item name, in the form group.name."
  [item group-name]
  (dm/assert!
   "expected groupable item"
   (valid-groupable-item? item))
  (update item :name #(str group-name "." %)))

(defn ungroup-item
  "Remove the first group from the item name."
  [item]
  (dm/assert!
   "expected groupable item"
   (valid-groupable-item? item))
  (update item :name #(-> %
                          (split-path)
                          (rest)
                          (join-path))))

(defn get-path
  "Get the groups part of the name. E.g. group.subgroup.name -> group.subrgoup"
  [item]
  (dm/assert!
   "expected groupable item"
   (valid-groupable-item? item))
  (-> (:name item)
      (split-path)
      (butlast)
      (join-path)))

(defn get-final-name
  "Get the final part of the name. E.g. group.subgroup.name -> name"
  [item]
  (dm/assert!
   "expected groupable item"
   (valid-groupable-item? item))
  (-> (:name item)
      (split-path)
      (last)))

(defn group-items
  "Convert a sequence of items in a nested structure like this:

    {'': [itemA itemB]
     'group1': {'': [item1A item1B]
                'subgroup11': {'': [item11A item11B item11C]}
                'subgroup12': {'': [item12A]}}
     'group2': {'subgroup21': {'': [item21A]}}}
  "
  [items & {:keys [reverse-sort?]}]
  (when (seq items)
    (reduce (fn [groups item]
              (let [pathv (split-path (:name item))]
                (update-in groups
                           pathv
                           (fn [group]
                             (if group
                               (cons group item)
                               item)))))
            (sorted-map-by (fn [key1 key2]               ;; TODO: this does not work well with ordered-map
                             (if reverse-sort?           ;;       we need to think a bit more of this
                               (compare key2 key1)
                               (compare key1 key2))))
            items)))
