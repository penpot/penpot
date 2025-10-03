;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.plugins.tokens
  (:require
   [app.common.data :as d]
   [app.common.types.tokens-lib :as ctob]
   [app.common.uuid :as uuid]
   [app.plugins.utils :as u]
   [app.util.object :as obj]))

(defn locate-theme
  [file-id id]
  (let [file (u/locate-file file-id)
        tokens-lib (->> file :data :tokens-lib)]
    (ctob/get-theme tokens-lib id)))

(defn locate-token-set
  [file-id set-id]
  (let [file (u/locate-file file-id)
        tokens-lib (->> file :data :tokens-lib)]
    (ctob/get-set tokens-lib set-id)))

(defn locate-token
  [file-id set-id token-id]
  (let [file (u/locate-file file-id)
        tokens-lib (->> file :data :tokens-lib)]
    (ctob/get-token tokens-lib set-id token-id)))

(defn token-proxy
  [plugin-id file-id set-id id]
  (obj/reify {:name "TokenSetProxy"}
    :$plugin {:enumerable false :get (constantly plugin-id)}
    :$file-id {:enumerable false :get (constantly file-id)}
    :$set-id {:enumerable false :get (constantly set-id)}
    :$id {:enumerable false :get (constantly id)}

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

    :applyToShape
    (fn [_property _shape]
      ;; TODO: validate input
      ;; TODO: Apply token to a shape
      )))

(defn token-set-proxy
  [plugin-id file-id set-id]
  (obj/reify {:name "TokenSetProxy"}
    :$plugin {:enumerable false :get (constantly plugin-id)}
    :$file-id {:enumerable false :get (constantly file-id)}
    :id {:this true :get (constantly set-id)}

    :tokens
    {:this true
     :get
     (fn [_]
       (let [file (u/locate-file file-id)
             tokens-lib (->> file :data :tokens-lib)]
         (->> (ctob/get-tokens tokens-lib set-id)
              (map #(token-proxy plugin-id file-id set-id (:id %)))
              (apply array))))}))

(defn theme-proxy
  [plugin-id file-id id]
  (obj/reify {:name "TokenThemeProxy"}
    :$plugin {:enumerable false :get (constantly plugin-id)}
    :$file-id {:enumerable false :get (constantly file-id)}

    :id {:this true :get (constantly id)}

    :group
    {:this true
     :get
     (fn [_]
       (let [theme (locate-theme file-id id)]
         (:group theme)))}

    :name
    {:this true
     :get
     (fn [_]
       (let [theme (locate-theme file-id id)]
         (:name theme)))}

    :active
    {:this true :get (fn [_])}

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
         (apply array (map #(theme-proxy plugin-id file-id (ctob/get-id %)) themes))))}

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
      (theme-proxy plugin-id file-id id))

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
      )))
