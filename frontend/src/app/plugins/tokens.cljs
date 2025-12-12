;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.plugins.tokens
  (:require
   [app.common.data.macros :as dm]
   [app.common.types.token :as cto]
   [app.common.types.tokens-lib :as ctob]
   [app.common.uuid :as uuid]
   [app.main.data.workspace.tokens.application :as dwta]
   [app.main.data.workspace.tokens.library-edit :as dwtl]
   [app.main.store :as st]
   [app.main.ui.workspace.tokens.management.create.form :as token-form]
   [app.main.ui.workspace.tokens.themes.create-modal :as theme-form]
   [app.plugins.utils :as u]
   [app.util.object :as obj]
   [clojure.datafy :refer [datafy]]))

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
       (let [tokens-lib (u/locate-tokens-lib file-id)
             errors     (token-form/validate-token-name
                         (ctob/get-tokens tokens-lib set-id)
                         value)]
         (cond
           (some? errors)
           (u/display-not-valid :name (first errors))

           :else
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

(defn token-set-proxy
  [plugin-id file-id id]
  (obj/reify {:name "TokenSetProxy"}
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
       (let [set (u/locate-token-set file-id id)]
         (cond
           (not (string? value))
           (u/display-not-valid :name value)

           :else
           (st/emit! (dwtl/update-token-set set value)))))}

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
       (let [file (u/locate-file file-id)
             tokens-lib (->> file :data :tokens-lib)]
         (->> (ctob/get-tokens tokens-lib id)
              (vals)
              (map #(token-proxy plugin-id file-id id (:id %)))
              (apply array))))}

    :tokensByType
    {:this true
     :enumerable false
     :get
     (fn [_]
       (let [file (u/locate-file file-id)
             tokens-lib (->> file :data :tokens-lib)
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
    (fn [type-str name value]
      (let [type (cto/dtcg-token-type->token-type type-str)]
        (cond
          (nil? type)
          (u/display-not-valid :addTokenType type-str)

          (not (string? name))
          (u/display-not-valid :addTokenName name)

          :else
          (let [token (ctob/make-token {:type type
                                        :name name
                                        :value value})]
            (st/emit! (dwtl/create-token id token))
            (token-proxy plugin-id file-id (:id set) (:id token))))))

    :duplicate
    (fn []
      (let [set  (u/locate-token-set file-id id)
            set' (ctob/make-token-set (-> (datafy set)
                                          (dissoc :id
                                                  :modified-at)))]
        (st/emit! (dwtl/create-token-set set'))
        (token-set-proxy plugin-id file-id (:id set'))))

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
       (let [theme (u/locate-token-theme file-id id)]
         (cond
           (not (string? value))
           (u/display-not-valid :group value)

           :else
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
             errors (theme-form/validate-theme-name
                     (u/locate-tokens-lib file-id)
                     (:group theme)
                     id
                     value)]
         (cond
           (some? errors)
           (u/display-not-valid :name (first errors))

           :else
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
       (let [file (u/locate-file file-id)
             tokens-lib (->> file :data :tokens-lib)
             themes (->> (ctob/get-themes tokens-lib)
                         (remove #(= (:id %) uuid/zero)))]
         (apply array (map #(token-theme-proxy plugin-id file-id (ctob/get-id %)) themes))))}

    :sets
    {:this true
     :enumerable false
     :get
     (fn [_]
       (let [file (u/locate-file file-id)
             tokens-lib (->> file :data :tokens-lib)
             sets (ctob/get-sets tokens-lib)]
         (apply array (map #(token-set-proxy plugin-id file-id (ctob/get-id %)) sets))))}

    :addTheme
    (fn [group name]
      (cond
        (not (string? group))
        (u/display-not-valid :addThemeGroup group)

        (not (string? name))
        (u/display-not-valid :addThemeName name)

        :else
        (let [theme (ctob/make-token-theme {:group group
                                            :name name})]
          (st/emit! (dwtl/create-token-theme theme))
          (token-theme-proxy plugin-id file-id (:id theme)))))

    :addSet
    (fn [name]
      (cond
        (not (string? name))
        (u/display-not-valid :addSetName name)

        :else
        (let [set (ctob/make-token-set {:name name})]
          (st/emit! (dwtl/create-token-set set))
          (token-set-proxy plugin-id file-id (:id set)))))

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

