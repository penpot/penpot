;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.plugins.tokens
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.types.token :as cto]
   [app.common.types.tokens-lib :as ctob]
   [app.common.uuid :as uuid]
   [app.main.data.workspace.tokens.application :as dwta]
   [app.main.data.workspace.tokens.library-edit :as dwtl]
   [app.main.store :as st]
   [app.plugins.utils :as u]
   [app.util.object :as obj]))

(defn locate-tokens-lib
  [file-id]
  (let [file (u/locate-file file-id)]
    (->> file :data :tokens-lib)))

(defn locate-token-theme
  [file-id id]
  (let [tokens-lib (locate-tokens-lib file-id)]
    (ctob/get-theme tokens-lib id)))

(defn locate-token-set
  [file-id set-id]
  (let [tokens-lib (locate-tokens-lib file-id)]
    (ctob/get-set tokens-lib set-id)))

(defn locate-token
  [file-id set-id token-id]
  (let [tokens-lib (locate-tokens-lib file-id)]
    (ctob/get-token tokens-lib set-id token-id)))

(defn- apply-token-to-shapes
  [file-id set-id id attrs shape-ids]
  (let [token (locate-token file-id set-id id)
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
       (let [token (locate-token file-id set-id id)]
         (ctob/get-name token)))}

    :type
    {:this true
     :get
     (fn [_]
       (let [token (locate-token file-id set-id id)]
         (-> (:type token) d/name)))}

    :value
    {:this true
     :get
     (fn [_]
       (let [token (locate-token file-id set-id id)]
         (:value token)))}

    :description
    {:this true
     :get
     (fn [_]
       (let [token (locate-token file-id set-id id)]
         (ctob/get-description token)))}

    :applyToShapes
    (fn [attrs shapes]
      (apply-token-to-shapes file-id set-id id attrs (map :id shapes)))

    :applyToSelected
    (fn [attrs]
      (let [selected (get-in @st/state [:workspace-local :selected])]
        (apply-token-to-shapes file-id set-id id attrs selected)))))

(defn token-set-proxy
  [plugin-id file-id id]
  (obj/reify {:name "TokenSetProxy"}
    :$plugin {:enumerable false :get (constantly plugin-id)}
    :$file-id {:enumerable false :get (constantly file-id)}

    :id
    {:get #(dm/str id)}

    :name
    {:this true
     :get
     (fn [_]
       (let [set (locate-token-set file-id id)]
         (ctob/get-name set)))}

    :active
    {:this true
     :get
     (fn [_]
       (let [tokens-lib (locate-tokens-lib file-id)
             set        (locate-token-set file-id id)]
         (ctob/token-set-active? tokens-lib (ctob/get-name set))))}

    :toggleActive
    (fn [_]
      (let [set (locate-token-set file-id id)]
        (st/emit! (dwtl/toggle-token-set (ctob/get-name set)))))

    :tokens
    {:this true
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
     :get
     (fn [_]
       (let [file (u/locate-file file-id)
             tokens-lib (->> file :data :tokens-lib)
             tokens (ctob/get-tokens tokens-lib id)]
         (->> tokens
              (vals)
              (sort-by :name)
              (group-by :type)
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
              token (locate-token file-id id token-id)]
          (when (some? token)
            (token-proxy plugin-id file-id id token-id)))))))

(defn token-theme-proxy
  [plugin-id file-id id]
  (obj/reify {:name "TokenThemeProxy"}
    :$plugin {:enumerable false :get (constantly plugin-id)}
    :$file-id {:enumerable false :get (constantly file-id)}

    :id
    {:get #(dm/str id)}

    :group
    {:this true
     :get
     (fn [_]
       (let [theme (locate-token-theme file-id id)]
         (:group theme)))}

    :name
    {:this true
     :get
     (fn [_]
       (let [theme (locate-token-theme file-id id)]
         (:name theme)))}

    :active
    {:this true
     :get
     (fn [_]
       (let [tokens-lib (locate-tokens-lib file-id)]
         (ctob/theme-active? tokens-lib id)))}

    :toggleActive
    (fn [_]
      (st/emit! (dwtl/toggle-token-theme-active? id)))

    :activeSets
    {:this true :get (fn [_])}

    :addSet
    (fn [_tokenSet]
      ;; TODO
      )

    :removeSet
    (fn [_tokenSet]
      ;; TODO
      )

    :duplicate
    (fn []
      ;; TODO
      )

    :remove
    (fn []
      ;; TODO
      )))

(defn tokens-context
  [plugin-id file-id]
  (obj/reify {:name "TokensContext"}
    :$plugin {:enumerable false :get (constantly plugin-id)}
    :$id {:enumerable false :get (constantly file-id)}

    :themes
    {:this true
     :get
     (fn [_]
       (let [file (u/locate-file file-id)
             tokens-lib (->> file :data :tokens-lib)
             themes (->> (ctob/get-themes tokens-lib)
                         (remove #(= (:id %) uuid/zero)))]
         (apply array (map #(token-theme-proxy plugin-id file-id (ctob/get-id %)) themes))))}

    :sets
    {:this true
     :get
     (fn [_]
       (let [file (u/locate-file file-id)
             tokens-lib (->> file :data :tokens-lib)
             sets (ctob/get-sets tokens-lib)]
         (apply array (map #(token-set-proxy plugin-id file-id (ctob/get-id %)) sets))))}

    :addTheme
    (fn [id]
      ;; TODO
      (token-theme-proxy plugin-id file-id id))

    :removeTheme
    (fn [_theme]
      ;; TODO
      )

    :addSet
    (fn [id]
      ;; TODO
      (token-set-proxy plugin-id file-id id))

    :removeSet
    (fn [_set]
      ;; TODO
      )

    :getThemeById
    (fn [theme-id]
      (cond
        (not (string? theme-id))
        (u/display-not-valid :getThemeById theme-id)

        :else
        (let [theme-id (uuid/parse theme-id)
              theme (locate-token-theme file-id theme-id)]
          (when (some? theme)
            (token-theme-proxy plugin-id file-id theme-id)))))

    :getSetById
    (fn [set-id]
      (cond
        (not (string? set-id))
        (u/display-not-valid :getSetById set-id)

        :else
        (let [set-id (uuid/parse set-id)
              set (locate-token-set file-id set-id)]
          (when (some? set)
            (token-set-proxy plugin-id file-id set-id)))))))

