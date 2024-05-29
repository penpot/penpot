;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.plugins.library
  "RPC for plugins runtime."
  (:require
   [app.common.colors :as cc]
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.record :as cr]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.store :as st]
   [app.plugins.utils :as u]))

(deftype LibraryColorProxy [$file $id]
  Object

  (asFill [_]
    (let [color (u/locate-library-color $file $id)]
      (u/to-js
       (d/without-nils
        {:fill-color (:color color)
         :fill-opacity (:opacity color)
         :fill-color-gradient (:gradient color)
         :fill-color-ref-file $file
         :fill-color-ref-id $id
         :fill-image (:image color)}))))

  (asStroke [_]
    (let [color (u/locate-library-color $file $id)]
      (u/to-js
       (d/without-nils
        {:stroke-color (:color color)
         :stroke-opacity (:opacity color)
         :stroke-color-gradient (:gradient color)
         :stroke-color-ref-file $file
         :stroke-color-ref-id $id
         :stroke-image (:image color)
         :stroke-style :solid
         :stroke-alignment :inner})))))

(defn lib-color-proxy
  [file-id id]
  (assert (uuid? file-id))
  (assert (uuid? id))

  (cr/add-properties!
   (LibraryColorProxy. file-id id)
   {:name "$id" :enumerable false :get (constantly id)}
   {:name "$file" :enumerable false :get (constantly file-id)}

   {:name "id" :get (fn [_] (dm/str id))}

   {:name "name"
    :get #(-> % u/proxy->library-color :name)
    :set
    (fn [_ value]
      (if (and (some? value) (string? value))
        (st/emit! (dwl/rename-color file-id id value))
        (u/display-not-valid :library-color-name value)))}

   {:name "color"
    :get #(-> % u/proxy->library-color :color)
    :set
    (fn [self value]
      (if (and (some? value) (string? value) (cc/valid-hex-color? value))
        (let [color (-> (u/proxy->library-color self)
                        (assoc :color value))]
          (st/emit! (dwl/update-color color file-id)))
        (u/display-not-valid :library-color-color value)))}

   {:name "opacity"
    :get #(-> % u/proxy->library-color :opacity)
    :set
    (fn [self value]
      (if (and (some? value) (number? value) (>= value 0) (<= value 1))
        (let [color (-> (u/proxy->library-color self)
                        (assoc :opacity value))]
          (st/emit! (dwl/update-color color file-id)))
        (u/display-not-valid :library-color-color value)))}

   {:name "gradient"
    :get #(-> % u/proxy->library-color :gradient u/to-js)}

   {:name "image"
    :get #(-> % u/proxy->library-color :image u/to-js)}))

(deftype LibraryTypographyProxy [$file $id]
  Object)

(defn lib-typography-proxy
  [file-id id]
  (assert (uuid? file-id))
  (assert (uuid? id))

  (cr/add-properties!
   (LibraryTypographyProxy. file-id id)
   {:name "$id" :enumerable false :get (constantly id)}
   {:name "$file" :enumerable false :get (constantly file-id)}
   {:name "id" :get (fn [_] (dm/str id))}
   {:name "name"
    :get #(-> % u/proxy->library-typography :name)}))

(deftype LibraryComponentProxy [$file $id]
  Object)

(defn lib-component-proxy
  [file-id id]
  (assert (uuid? file-id))
  (assert (uuid? id))

  (cr/add-properties!
   (LibraryComponentProxy. file-id id)
   {:name "$id" :enumerable false :get (constantly id)}
   {:name "$file" :enumerable false :get (constantly file-id)}
   {:name "id" :get (fn [_] (dm/str id))}
   {:name "name" :get #(-> % u/proxy->library-component :name)}))

(deftype Library [$id]
  Object)

(defn library-proxy
  [file-id]
  (assert (uuid? file-id) "File id not valid")

  (cr/add-properties!
   (Library. file-id)
   {:name "$file" :enumerable false :get (constantly file-id)}

   {:name "id"
    :get #(-> % u/proxy->file :id str)}

   {:name "name"
    :get #(-> % u/proxy->file :name)}

   {:name "colors"
    :get
    (fn [_]
      (let [file (u/locate-file file-id)
            colors (->> file :data :colors keys (map #(lib-color-proxy file-id %)))]
        (apply array colors)))}

   {:name "typographies"
    :get
    (fn [_]
      (let [file (u/locate-file file-id)
            typographies (->> file :data :typographies keys (map #(lib-typography-proxy file-id %)))]
        (apply array typographies)))}

   {:name "components"
    :get
    (fn [_]
      (let [file (u/locate-file file-id)
            components (->> file :data :componentes keys (map #(lib-component-proxy file-id %)))]
        (apply array components)))}))

(deftype PenpotLibrarySubcontext []
  Object
  (find
    [_ _name])

  (find [_]))

(defn library-subcontext
  []
  (cr/add-properties!
   (PenpotLibrarySubcontext.)
   {:name "local" :get
    (fn [_]
      (library-proxy (:current-file-id @st/state)))}

   {:name "connected" :get
    (fn [_]
      (let [libraries (get @st/state :workspace-libraries)]
        (apply array (->> libraries vals (map library-proxy)))))}))
