;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.plugins.tokens
  (:require
   [app.common.data.macros :as dm]
   [app.common.files.tokens :as cfo]
   [app.common.schema :as sm]
   [app.common.types.token :as cto]
   [app.common.types.tokens-lib :as ctob]
   [app.common.uuid :as uuid]
   [app.main.data.style-dictionary :as sd]
   [app.main.data.workspace.tokens.application :as dwta]
   [app.main.data.workspace.tokens.library-edit :as dwtl]
   [app.main.store :as st]
   [app.plugins.shape :as shape]
   [app.plugins.utils :as u]
   [app.util.object :as obj]
   [beicon.v2.core :as rx]
   [clojure.datafy :refer [datafy]]))

;; === Token

(defn- apply-token-to-shapes
  [file-id set-id id shape-ids attrs]
  (let [token (u/locate-token file-id set-id id)]
    (if (some #(not (cto/token-attr? %)) attrs)
      (u/display-not-valid :applyToSelected attrs)
      (st/emit!
       (dwta/toggle-token {:token token
                           :attrs attrs
                           :shape-ids shape-ids
                           :expand-with-children false})))))

(defn token-proxy? [p]
  (obj/type-of? p "TokenProxy"))

(defn token-proxy
  [plugin-id file-id set-id id]
  (obj/reify {:name "TokenProxy"
              :wrap u/wrap-errors}
    :$plugin {:enumerable false :get (constantly plugin-id)}
    :$file-id {:enumerable false :get (constantly file-id)}
    :$set-id {:enumerable false :get (constantly set-id)}
    :$id {:enumerable false :get (constantly id)}

    :id
    {:get #(dm/str id)}

    :name
    {:this true
     :get
     (fn [_]
       (let [token (u/locate-token file-id set-id id)]
         (ctob/get-name token)))
     :schema (cfo/make-token-name-schema
              (some-> (u/locate-tokens-lib file-id)
                      (ctob/get-tokens set-id)))
     :set
     (fn [_ value]
       (st/emit! (dwtl/update-token set-id id {:name value})))}

    :type
    {:this true
     :get
     (fn [_]
       (let [token (u/locate-token file-id set-id id)]
         (-> (:type token) (cto/token-type->dtcg-token-type))))}

    :value
    {:this true
     :get
     (fn [_]
       (let [token (u/locate-token file-id set-id id)]
         (:value token)))
     :schema (let [token (u/locate-token file-id set-id id)]
               (cfo/make-token-value-schema (:type token)))
     :set
     (fn [_ value]
       (st/emit! (dwtl/update-token set-id id {:value value})))}

    :description
    {:this true
     :get
     (fn [_]
       (let [token (u/locate-token file-id set-id id)]
         (ctob/get-description token)))
     :schema cfo/schema:token-description
     :set
     (fn [_ value]
       (st/emit! (dwtl/update-token set-id id {:description value})))}

    :duplicate
    (fn []
      ;; TODO:
      ;;  - add function duplicate-token in tokens-lib, that allows to specify the new id
      ;;  - use this function in dwtl/duplicate-token
      ;;  - return the new token proxy using the locally forced id
      ;;  - do the same with sets and themes
      (let [token  (u/locate-token file-id set-id id)
            token' (ctob/make-token (-> (datafy token)
                                        (dissoc :id
                                                :modified-at)))]
        (st/emit! (dwtl/create-token set-id token'))
        (token-proxy plugin-id file-id set-id (:id token'))))

    :remove
    (fn []
      (st/emit! (dwtl/delete-token set-id id)))

    :applyToShapes
    {:schema [:tuple
              [:vector [:fn shape/shape-proxy?]]
              [:maybe [:set ::sm/keyword]]]
     :fn (fn [shapes attrs]
           (apply-token-to-shapes file-id set-id id (map :id shapes) attrs))}

    :applyToSelected
    {:schema [:tuple [:maybe [:set ::sm/keyword]]]
     :fn (fn [attrs]
           (let [selected (get-in @st/state [:workspace-local :selected])]
             (apply-token-to-shapes file-id set-id id selected attrs)))}))

;; === Token Set

(defn token-set-proxy? [p]
  (obj/type-of? p "TokenSetProxy"))

(defn token-set-proxy
  [plugin-id file-id id]
  (obj/reify {:name "TokenSetProxy"
              :wrap u/wrap-errors}
    :$plugin {:enumerable false :get (constantly plugin-id)}
    :$file-id {:enumerable false :get (constantly file-id)}
    :$id {:enumerable false :get (constantly id)}

    :id
    {:get #(dm/str id)}

    :name
    {:this true
     :get
     (fn [_]
       (let [set (u/locate-token-set file-id id)]
         (ctob/get-name set)))
     :schema (cfo/make-token-set-name-schema
              (u/locate-tokens-lib file-id)
              id)
     :set
     (fn [_ name]
       (let [set (u/locate-token-set file-id id)]
         (st/emit! (dwtl/rename-token-set set name))))}

    :active
    {:this true
     :enumerable false
     :get
     (fn [_]
       (let [tokens-lib (u/locate-tokens-lib file-id)
             set        (u/locate-token-set file-id id)]
         (ctob/token-set-active? tokens-lib (ctob/get-name set))))
     :schema ::sm/boolean
     :set
     (fn [_ value]
       (let [set (u/locate-token-set file-id id)]
         (st/emit! (dwtl/set-enabled-token-set (ctob/get-name set) value))))}

    :toggleActive
    (fn [_]
      (let [set (u/locate-token-set file-id id)]
        (st/emit! (dwtl/toggle-token-set (ctob/get-name set)))))

    :tokens
    {:this true
     :enumerable false
     :get
     (fn [_]
       (let [tokens-lib (u/locate-tokens-lib file-id)]
         (->> (ctob/get-tokens tokens-lib id)
              (vals)
              (map #(token-proxy plugin-id file-id id (:id %)))
              (apply array))))}

    :tokensByType
    {:this true
     :enumerable false
     :get
     (fn [_]
       (let [tokens-lib (u/locate-tokens-lib file-id)
             tokens (ctob/get-tokens tokens-lib id)]
         (->> tokens
              (vals)
              (sort-by :name)
              (group-by #(cto/token-type->dtcg-token-type (:type %)))
              (into [])
              (mapv (fn [[type tokens]]
                      #js [(name type)
                           (->> tokens
                                (map #(token-proxy plugin-id file-id id (:id %)))
                                (apply array))]))
              (apply array))))}

    :getTokenById
    {:schema [:tuple ::sm/uuid]
     :fn (fn [token-id]
           (let [token (u/locate-token file-id id token-id)]
             (when (some? token)
               (token-proxy plugin-id file-id id token-id))))}

    :addToken
    {:schema (fn [args]
               [:tuple (-> (cfo/make-token-schema
                            (-> (u/locate-tokens-lib file-id) (ctob/get-tokens id))
                            (cto/dtcg-token-type->token-type (-> args (first) (get "type"))))
                           ;; Don't allow plugins to set the id
                           (sm/dissoc-key :id)
                           ;; Instruct the json decoder in obj/reify not to process map keys (:key-fn below)
                           ;; and set a converter that changes DTCG types to internal types (:decode/json).
                           ;; E.g. "FontFamilies" -> :font-family or "BorderWidth" -> :stroke-width
                           (sm/update-properties assoc :decode/json cfo/convert-dtcg-token))])
     :decode/options {:key-fn identity}
     :fn (fn [attrs]
           (let [tokens-lib (u/locate-tokens-lib file-id)
                 tokens-tree (ctob/get-tokens-in-active-sets tokens-lib)
                 token (ctob/make-token attrs)]
             (->> (assoc tokens-tree (:name token) token)
                  (sd/resolve-tokens-interactive)
                  (rx/subs!
                   (fn [resolved-tokens]
                     (let [{:keys [errors resolved-value] :as resolved-token} (get resolved-tokens (:name token))]
                       (if resolved-value
                         (st/emit! (dwtl/create-token id token))
                         (u/display-not-valid :addToken (str errors)))))))
             ;; TODO: as the addToken function is synchronous, we must return the newly created
             ;;       token even if the validator will throw it away if the resolution fails.
             ;;       This will be solved with the TokenScript resolver, that is syncronous.
             (token-proxy plugin-id file-id id (:id token))))}

    :duplicate
    (fn []
      (st/emit! (dwtl/duplicate-token-set id)))

    :remove
    (fn []
      (st/emit! (dwtl/delete-token-set id)))))

(defn token-theme-proxy? [p]
  (obj/type-of? p "TokenThemeProxy"))

(defn token-theme-proxy
  [plugin-id file-id id]
  (obj/reify {:name "TokenThemeProxy"
              :wrap u/wrap-errors}
    :$plugin {:enumerable false :get (constantly plugin-id)}
    :$file-id {:enumerable false :get (constantly file-id)}
    :$id {:enumerable false :get (constantly id)}

    :id
    {:get #(dm/str id)}

    :external-id
    {:this true
     :get
     (fn [_]
       (let [theme (u/locate-token-theme file-id id)]
         (:external-id theme)))}

    :group
    {:this true
     :get
     (fn [_]
       (let [theme (u/locate-token-theme file-id id)]
         (:group theme)))
     :schema (let [theme (u/locate-token-theme file-id id)]
               (cfo/make-token-theme-group-schema
                (u/locate-tokens-lib file-id)
                (:name theme)
                (:id theme)))
     :set
     (fn [_ group]
       (let [theme (u/locate-token-theme file-id id)]
         (st/emit! (dwtl/update-token-theme id (assoc theme :group group)))))}

    :name
    {:this true
     :get
     (fn [_]
       (let [theme (u/locate-token-theme file-id id)]
         (:name theme)))
     :schema (let [theme (u/locate-token-theme file-id id)]
               (cfo/make-token-theme-name-schema
                (u/locate-tokens-lib file-id)
                (:id theme)
                (:group theme)))
     :set
     (fn [_ name]
       (let [theme (u/locate-token-theme file-id id)]
         (when name
           (st/emit! (dwtl/update-token-theme id (assoc theme :name name))))))}

    :active
    {:this true
     :enumerable false
     :get
     (fn [_]
       (let [tokens-lib (u/locate-tokens-lib file-id)]
         (ctob/theme-active? tokens-lib id)))
     :schema ::sm/boolean
     :set
     (fn [_ value]
       (st/emit! (dwtl/set-token-theme-active id value)))}

    :toggleActive
    (fn [_]
      (st/emit! (dwtl/toggle-token-theme-active id)))

    :activeSets
    {:this true :get (fn [_])}

    :addSet
    {:schema [:tuple [:fn token-set-proxy?]]
     :fn (fn [tokenSet]
           (let [theme (u/locate-token-theme file-id id)]
             (st/emit! (dwtl/update-token-theme id (ctob/enable-set theme (obj/get tokenSet :name))))))}

    :removeSet
    {:schema [:tuple [:fn token-set-proxy?]]
     :fn (fn [tokenSet]
           (let [theme (u/locate-token-theme file-id id)]
             (st/emit! (dwtl/update-token-theme id (ctob/disable-set theme (obj/get tokenSet :name))))))}

    :duplicate
    (fn []
      (let [theme  (u/locate-token-theme file-id id)
            theme' (ctob/make-token-theme (-> (datafy theme)
                                              (dissoc :id
                                                      :modified-at)))]
        (st/emit! (dwtl/create-token-theme theme'))
        (token-theme-proxy plugin-id file-id (:id theme'))))

    :remove
    (fn []
      (st/emit! (dwtl/delete-token-theme id)))))

(defn tokens-catalog
  [plugin-id file-id]
  (obj/reify {:name "TokensCatalog"
              :wrap u/wrap-errors}
    :$plugin {:enumerable false :get (constantly plugin-id)}
    :$id {:enumerable false :get (constantly file-id)}

    :themes
    {:this true
     :enumerable false
     :get
     (fn [_]
       (let [tokens-lib (u/locate-tokens-lib file-id)
             themes (when tokens-lib
                      (->> (ctob/get-themes tokens-lib)
                           (remove #(= (:id %) uuid/zero))))]
         (apply array (map #(token-theme-proxy plugin-id file-id (ctob/get-id %)) themes))))}

    :sets
    {:this true
     :enumerable false
     :get
     (fn [_]
       (let [tokens-lib (u/locate-tokens-lib file-id)
             sets (when tokens-lib
                    (ctob/get-sets tokens-lib))]
         (apply array (map #(token-set-proxy plugin-id file-id (ctob/get-id %)) sets))))}

    :addTheme
    {:schema (fn [attrs]
               [:tuple (-> (sm/schema (cfo/make-token-theme-schema
                                       (u/locate-tokens-lib file-id)
                                       (or (obj/get attrs "group") "")
                                       (or (obj/get attrs "name") "")
                                       nil))
                           (sm/dissoc-key :id))]) ;; We don't allow plugins to set the id
     :fn (fn [attrs]
           (let [theme (ctob/make-token-theme attrs)]
             (st/emit! (dwtl/create-token-theme theme))
             (token-theme-proxy plugin-id file-id (:id theme))))}

    :addSet
    {:schema [:tuple (-> (sm/schema (cfo/make-token-set-schema
                                     (u/locate-tokens-lib file-id)
                                     nil))
                         (sm/dissoc-key :id))] ;; We don't allow plugins to set the id

     :fn (fn [attrs]
           (let [attrs (update attrs :name ctob/normalize-set-name)
                 set (ctob/make-token-set attrs)]
             (st/emit! (dwtl/create-token-set set))
             (token-set-proxy plugin-id file-id (ctob/get-id set))))}

    :getThemeById
    {:schema [:tuple ::sm/uuid]
     :fn (fn [theme-id]
           (let [theme (u/locate-token-theme file-id theme-id)]
             (when (some? theme)
               (token-theme-proxy plugin-id file-id theme-id))))}

    :getSetById
    {:schema [:tuple ::sm/uuid]
     :fn (fn [set-id]
           (let [set (u/locate-token-set file-id set-id)]
             (when (some? set)
               (token-set-proxy plugin-id file-id set-id))))}))

