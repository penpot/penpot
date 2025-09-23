;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.plugins.tokens
  (:require
   [app.common.data :as d]
   [app.common.types.tokens-lib :as cttl]
   [app.common.uuid :as uuid]
   [app.plugins.utils :as u]
   [app.util.object :as obj]))

(defn locate-theme
  [file-id group name]
  (let [file (u/locate-file file-id)
        tokens-lib (->> file :data :tokens-lib)]
    (cttl/get-theme tokens-lib group name)))

(defn locate-token-set
  [file-id set-name]
  (let [file (u/locate-file file-id)
        tokens-lib (->> file :data :tokens-lib)]
    (cttl/get-set tokens-lib set-name)))

(defn locate-token
  [file-id set-name token-id]
  (let [file (u/locate-file file-id)
        tokens-lib (->> file :data :tokens-lib)]
    (cttl/get-token-in-set tokens-lib set-name token-id)))

(defn token-proxy
  [plugin-id file-id set-name id]
  (obj/reify {:name "TokenSetProxy"}
    :$plugin {:enumerable false :get (constantly plugin-id)}
    :$file-id {:enumerable false :get (constantly file-id)}
    :$set-name {:enumerable false :get (constantly set-name)}
    :$id {:enumerable false :get (constantly id)}

    :name
    {:this true
     :get
     (fn [_]
       (let [token (locate-token file-id set-name id)]
         (cttl/get-name token)))}

    :type
    {:this true
     :get
     (fn [_]
       (let [token (locate-token file-id set-name id)]
         (-> (:type token) d/name)))}

    :value
    {:this true
     :get
     (fn [_]
       (let [token (locate-token file-id set-name id)]
         (:value token)))}

    :description
    {:this true
     :get
     (fn [_]
       (let [token (locate-token file-id set-name id)]
         (cttl/get-description token)))}

    :applyToShape
    (fn [_property _shape]
      ;; TODO: validate input
      ;; TODO: Apply token to a shape
      )))

(defn token-set-proxy
  [plugin-id file-id set-name]
  (obj/reify {:name "TokenSetProxy"}
    :$plugin {:enumerable false :get (constantly plugin-id)}
    :$file-id {:enumerable false :get (constantly file-id)}
    :name {:this true :get (constantly set-name)}

    :tokens
    {:this true
     :get
     (fn [_]
       (let [file (u/locate-file file-id)
             tokens-lib (->> file :data :tokens-lib)
             token-set (cttl/get-set tokens-lib set-name)]
         (->> (cttl/get-tokens token-set)
              (map #(token-proxy plugin-id file-id set-name (:id %)))
              (apply array))))}))

(defn theme-proxy
  [plugin-id file-id group name]
  (obj/reify {:name "TokenThemeProxy"}
    :$plugin {:enumerable false :get (constantly plugin-id)}
    :$file-id {:enumerable false :get (constantly file-id)}

    :group {:this true :get (constantly group)}
    :name {:this true :get (constantly name)}

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
             themes (->> (cttl/get-themes tokens-lib)
                         (remove #(= (:id %) uuid/zero)))]
         (apply array (map #(theme-proxy plugin-id file-id (:group %) (:name %)) themes))))}

    :sets
    {:this true
     :get
     (fn [_]
       (let [file (u/locate-file file-id)
             tokens-lib (->> file :data :tokens-lib)
             sets (cttl/get-sets tokens-lib)]
         (apply array (map #(token-set-proxy plugin-id file-id (cttl/get-name %)) sets))))}

    :addTheme
    (fn [name]
      ;; TODO
      (theme-proxy plugin-id file-id "" name))

    :removeTheme
    (fn [_theme]
      ;; TODO
      )

    :addSet
    (fn [name]
      ;; TODO
      (token-set-proxy plugin-id file-id name))

    :removeSet
    (fn [_set]
      ;; TODO
      )))
