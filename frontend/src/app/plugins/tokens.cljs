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
   [app.plugins.utils :as u]
   [app.util.object :as obj]
   [beicon.v2.core :as rx]
   [clojure.datafy :refer [datafy]]))

;; === Token

(defn- apply-token-to-shapes
  [file-id set-id id shape-ids attrs]
  (let [token (u/locate-token file-id set-id id)
        kw-attrs (into #{} (map keyword attrs))]
    (if (some #(not (cto/token-attr? %)) kw-attrs)
      (u/display-not-valid :applyToSelected attrs)
      (st/emit!
       (dwta/toggle-token {:token token
                           :attrs kw-attrs
                           :shape-ids shape-ids
                           :expand-with-children false})))))

(defn token-proxy
  [plugin-id file-id set-id id]
  (obj/reify {:name "TokenSetProxy"}
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
     :set
     (fn [_ value]
       (let [name (u/coerce-1 value
                              (cfo/make-token-name-schema
                               (-> (u/locate-tokens-lib file-id)
                                   (ctob/get-tokens set-id)))
                              :name
                              "Invalid token name")]
         (when name
           (st/emit! (dwtl/update-token set-id id {:name value})))))}

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
         (:value token)))}

    :description
    {:this true
     :get
     (fn [_]
       (let [token (u/locate-token file-id set-id id)]
         (ctob/get-description token)))}

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
    (fn [shapes attrs]
      (apply-token-to-shapes file-id set-id id (map :id shapes) attrs))

    :applyToSelected
    (fn [attrs]
      (let [selected (get-in @st/state [:workspace-local :selected])]
        (apply-token-to-shapes file-id set-id id selected attrs)))))


;; === Token Set

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
     :set
     (fn [_ value]
       (let [set (u/locate-token-set file-id id)
             name (u/coerce-1 value
                              (cfo/make-token-set-name-schema
                               (u/locate-tokens-lib file-id)
                               id)
                              :setTokenSet
                              "Invalid token set name")]
         (when name
           (st/emit! (dwtl/rename-token-set set name)))))}

    :active
    {:this true
     :enumerable false
     :get
     (fn [_]
       (let [tokens-lib (u/locate-tokens-lib file-id)
             set        (u/locate-token-set file-id id)]
         (ctob/token-set-active? tokens-lib (ctob/get-name set))))
     :set
     (fn [_ value]
       (let [value (u/coerce-1 value
                               (sm/schema [:boolean])
                               :setActiveSet
                               value)]
         (when (some? value)
           (let [set (u/locate-token-set file-id id)]
             (st/emit! (dwtl/set-enabled-token-set (ctob/get-name set) value))))))}

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
    (fn [token-id]
      (cond
        (not (string? token-id))
        (u/display-not-valid :getTokenById token-id)

        :else
        (let [token-id (uuid/parse token-id)
              token (u/locate-token file-id id token-id)]
          (when (some? token)
            (token-proxy plugin-id file-id id token-id)))))

    :addToken
    {:schema [:tuple (-> (cfo/make-token-schema
                          (-> (u/locate-tokens-lib file-id)
                              (ctob/get-tokens id)))
                         ;; Don't allow plugins to set the id
                         (sm/dissoc-key :id)
                         ;; Instruct the json decoder in obj/reify not to process map keys (:key-fn)
                         ;; and set a converter that changes DTCG types to internal types (:decode/json).
                         ;; E.g. "FontFamilies" -> :font-family or "BorderWidth" -> :stroke-width
                         (sm/update-properties assoc :decode/json cfo/convert-dtcg-token))]
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
             (token-proxy plugin-id file-id (:id set) (:id token))))}
                                                                    
    :duplicate
    (fn []
      (st/emit! (dwtl/duplicate-token-set id)))

    :remove
    (fn []
      (st/emit! (dwtl/delete-token-set id)))))

(defn token-theme-proxy
  [plugin-id file-id id]
  (obj/reify {:name "TokenThemeProxy"}
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
     :set
     (fn [_ value]
       (let [theme (u/locate-token-theme file-id id)
             group (u/coerce-1 value
                               (cfo/make-token-theme-group-schema
                                (u/locate-tokens-lib file-id)
                                (:name theme)
                                (:id theme))
                               :group
                               "Invalid token theme group")]
         (when group
           (st/emit! (dwtl/update-token-theme id (assoc theme :group value))))))}

    :name
    {:this true
     :get
     (fn [_]
       (let [theme (u/locate-token-theme file-id id)]
         (:name theme)))
     :set
     (fn [_ value]
       (let [theme (u/locate-token-theme file-id id)
             name  (u/coerce-1 value
                               (cfo/make-token-theme-name-schema
                                (u/locate-tokens-lib file-id)
                                (:id theme)
                                (:group theme))
                               :name
                               "Invalid token theme name")]
         (when name
           (st/emit! (dwtl/update-token-theme id (assoc theme :name value))))))}

    :active
    {:this true
     :enumerable false
     :get
     (fn [_]
       (let [tokens-lib (u/locate-tokens-lib file-id)]
         (ctob/theme-active? tokens-lib id)))
     :set
     (fn [_ value]
       (st/emit! (dwtl/set-token-theme-active id value)))}

    :toggleActive
    (fn [_]
      (st/emit! (dwtl/toggle-token-theme-active id)))

    :activeSets
    {:this true :get (fn [_])}

    :addSet
    (fn [tokenSet]
      (let [theme (u/locate-token-theme file-id id)]
        (st/emit! (dwtl/update-token-theme id (ctob/enable-set theme (obj/get tokenSet :name))))))

    :removeSet
    (fn [tokenSet]
      (let [theme (u/locate-token-theme file-id id)]
        (st/emit! (dwtl/update-token-theme id (ctob/disable-set theme (obj/get tokenSet :name))))))

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
  (obj/reify {:name "TokensCatalog"}
    :$plugin {:enumerable false :get (constantly plugin-id)}
    :$id {:enumerable false :get (constantly file-id)}

    :themes
    {:this true
     :enumerable false
     :get
     (fn [_]
       (let [tokens-lib (u/locate-tokens-lib file-id)
             themes (->> (ctob/get-themes tokens-lib)
                         (remove #(= (:id %) uuid/zero)))]
         (apply array (map #(token-theme-proxy plugin-id file-id (ctob/get-id %)) themes))))}

    :sets
    {:this true
     :enumerable false
     :get
     (fn [_]
       (let [tokens-lib (u/locate-tokens-lib file-id)
             sets (ctob/get-sets tokens-lib)]
         (apply array (map #(token-set-proxy plugin-id file-id (ctob/get-id %)) sets))))}

    :addTheme
    (fn [attrs]
      (let [schema (-> (sm/schema (cfo/make-token-theme-schema
                                   (u/locate-tokens-lib file-id)
                                   (or (obj/get attrs "group") "")
                                   (or (obj/get attrs "name") "")
                                   nil))
                       (sm/dissoc-key :id)) ;; We don't allow plugins to set the id
            attrs  (u/coerce attrs schema :addTheme "invalid theme attrs")]
        (when attrs
          (let [theme (ctob/make-token-theme attrs)]
            (st/emit! (dwtl/create-token-theme theme))
            (token-theme-proxy plugin-id file-id (:id theme))))))

    :addSet
    (fn [attrs]
      (obj/update! attrs "name" ctob/normalize-set-name)  ;; TODO: seems a quite weird way of doing this
      (let [schema (-> (sm/schema (cfo/make-token-set-schema
                                   (u/locate-tokens-lib file-id)
                                   nil))
                       (sm/dissoc-key :id)) ;; We don't allow plugins to set the id
            attrs  (u/coerce attrs schema :addSet "invalid set attrs")]
        (when attrs
          (let [set (ctob/make-token-set attrs)]
            (st/emit! (dwtl/create-token-set set))
            (token-set-proxy plugin-id file-id (ctob/get-id set))))))

    :getThemeById
    (fn [theme-id]
      (cond
        (not (string? theme-id))
        (u/display-not-valid :getThemeById theme-id)

        :else
        (let [theme-id (uuid/parse theme-id)
              theme (u/locate-token-theme file-id theme-id)]
          (when (some? theme)
            (token-theme-proxy plugin-id file-id theme-id)))))

    :getSetById
    (fn [set-id]
      (cond
        (not (string? set-id))
        (u/display-not-valid :getSetById set-id)

        :else
        (let [set-id (uuid/parse set-id)
              set (u/locate-token-set file-id set-id)]
          (when (some? set)
            (token-set-proxy plugin-id file-id set-id)))))))

