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
   [app.common.geom.point :as gpt]
   [app.common.record :as cr]
   [app.common.schema :as sm]
   [app.common.types.color :as ctc]
   [app.common.types.typography :as ctt]
   [app.common.uuid :as uuid]
   [app.main.data.workspace :as dw]
   [app.main.data.workspace.libraries :as dwl]
   [app.main.data.workspace.texts :as dwt]
   [app.main.store :as st]
   [app.plugins.shape :as shapes]
   [app.plugins.utils :as u]
   [app.util.object :as obj]))

(declare lib-color-proxy)
(declare lib-typography-proxy)

(deftype LibraryColorProxy [$plugin $file $id]
  Object

  (remove
    [_]
    (st/emit! (dwl/delete-color {:id $id})))

  (clone
    [_]
    (let [color-id (uuid/next)
          color (-> (u/locate-library-color $file $id)
                    (assoc :id color-id))]
      (st/emit! (dwl/add-color color {:rename? false}))
      (lib-color-proxy $plugin $id color-id)))

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
         :stroke-alignment :inner}))))

  (getPluginData
    [self key]
    (cond
      (not (string? key))
      (u/display-not-valid :color-plugin-data-key key)

      :else
      (let [color (u/proxy->library-color self)]
        (dm/get-in color [:plugin-data (keyword "plugin" (str $plugin)) key]))))

  (setPluginData
    [_ key value]
    (cond
      (not= $file (:current-file-id @st/state))
      (u/display-not-valid :color-edit-non-local-library $file)

      (not (string? key))
      (u/display-not-valid :color-plugin-data-key key)

      (and (some? value) (not (string? value)))
      (u/display-not-valid :color-plugin-data value)

      :else
      (st/emit! (dw/set-plugin-data $file :color $id (keyword "plugin" (str $plugin)) key value))))

  (getPluginDataKeys
    [self]
    (let [color (u/proxy->library-color self)]
      (apply array (keys (dm/get-in color [:plugin-data (keyword "plugin" (str $plugin))])))))

  (getSharedPluginData
    [self namespace key]
    (cond
      (not (string? namespace))
      (u/display-not-valid :color-plugin-data-namespace namespace)

      (not (string? key))
      (u/display-not-valid :color-plugin-data-key key)

      :else
      (let [color (u/proxy->library-color self)]
        (dm/get-in color [:plugin-data (keyword "shared" namespace) key]))))

  (setSharedPluginData
    [_ namespace key value]
    (cond
      (not= $file (:current-file-id @st/state))
      (u/display-not-valid :color-edit-non-local-library $file)

      (not (string? namespace))
      (u/display-not-valid :color-plugin-data-namespace namespace)

      (not (string? key))
      (u/display-not-valid :color-plugin-data-key key)

      (and (some? value) (not (string? value)))
      (u/display-not-valid :color-plugin-data value)

      :else
      (st/emit! (dw/set-plugin-data $file :color $id (keyword "shared" namespace) key value))))

  (getSharedPluginDataKeys
    [self namespace]
    (cond
      (not (string? namespace))
      (u/display-not-valid :color-plugin-data-namespace namespace)

      :else
      (let [color (u/proxy->library-color self)]
        (apply array (keys (dm/get-in color [:plugin-data (keyword "shared" namespace)])))))))

(defn lib-color-proxy
  [plugin-id file-id id]
  (assert (uuid? file-id))
  (assert (uuid? id))

  (cr/add-properties!
   (LibraryColorProxy. plugin-id file-id id)
   {:name "$plugin" :enumerable false :get (constantly plugin-id)}
   {:name "$id" :enumerable false :get (constantly id)}
   {:name "$file" :enumerable false :get (constantly file-id)}

   {:name "id" :get (fn [_] (dm/str id))}

   {:name "name"
    :get #(-> % u/proxy->library-color :name)
    :set
    (fn [self value]
      (if (and (some? value) (string? value))
        (let [color (u/proxy->library-color self)
              value (dm/str (d/nilv (:path color) "") " / " value)]
          (st/emit! (dwl/rename-color file-id id value)))
        (u/display-not-valid :library-color-name value)))}

   {:name "path"
    :get #(-> % u/proxy->library-color :path)
    :set
    (fn [self value]
      (if (and (some? value) (string? value))
        (let [color (-> (u/proxy->library-color self)
                        (update :name #(str value " / " %)))]
          (st/emit! (dwl/update-color color file-id)))
        (u/display-not-valid :library-color-path value)))}

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
        (u/display-not-valid :library-color-opacity value)))}

   {:name "gradient"
    :get #(-> % u/proxy->library-color :gradient u/to-js)
    :set
    (fn [self value]
      (let [value (u/from-js value)]
        (if (sm/fast-check! ::ctc/gradient value)
          (let [color (-> (u/proxy->library-color self)
                          (assoc :gradient value))]
            (st/emit! (dwl/update-color color file-id)))
          (u/display-not-valid :library-color-gradient value))))}

   {:name "image"
    :get #(-> % u/proxy->library-color :image u/to-js)
    :set
    (fn [self value]
      (let [value (u/from-js value)]
        (if (sm/fast-check! ::ctc/image-color value)
          (let [color (-> (u/proxy->library-color self)
                          (assoc :image value))]
            (st/emit! (dwl/update-color color file-id)))
          (u/display-not-valid :library-color-image value))))}))

(deftype LibraryTypographyProxy [$plugin $file $id]
  Object
  (remove
    [_]
    (st/emit! (dwl/delete-typography {:id $id})))

  (clone
    [_]
    (let [typo-id (uuid/next)
          typo (-> (u/locate-library-typography $file $id)
                   (assoc :id typo-id))]
      (st/emit! (dwl/add-typography typo false))
      (lib-typography-proxy $plugin $id typo-id)))

  (applyToText
    [_ shape]
    (let [shape-id   (obj/get shape "$id")
          typography (u/locate-library-typography $file $id)]
      (st/emit! (dwt/apply-typography #{shape-id} typography $file))))

  (applyToTextRange
    [_ _shape _from _to]
    ;; TODO
    )

  ;; PLUGIN DATA
  (getPluginData
    [self key]
    (cond
      (not (string? key))
      (u/display-not-valid :typography-plugin-data-key key)

      :else
      (let [typography (u/proxy->library-typography self)]
        (dm/get-in typography [:plugin-data (keyword "plugin" (str $plugin)) key]))))

  (setPluginData
    [_ key value]
    (cond
      (not= $file (:current-file-id @st/state))
      (u/display-not-valid :typography-edit-non-local-library $file)

      (not (string? key))
      (u/display-not-valid :typography-plugin-data-key key)

      (and (some? value) (not (string? value)))
      (u/display-not-valid :typography-plugin-data value)

      :else
      (st/emit! (dw/set-plugin-data $file :typography $id (keyword "plugin" (str $plugin)) key value))))

  (getPluginDataKeys
    [self]
    (let [typography (u/proxy->library-typography self)]
      (apply array (keys (dm/get-in typography [:plugin-data (keyword "plugin" (str $plugin))])))))

  (getSharedPluginData
    [self namespace key]
    (cond
      (not (string? namespace))
      (u/display-not-valid :typography-plugin-data-namespace namespace)

      (not (string? key))
      (u/display-not-valid :typography-plugin-data-key key)

      :else
      (let [typography (u/proxy->library-typography self)]
        (dm/get-in typography [:plugin-data (keyword "shared" namespace) key]))))

  (setSharedPluginData
    [_ namespace key value]
    (cond
      (not= $file (:current-file-id @st/state))
      (u/display-not-valid :typography-edit-non-local-library $file)

      (not (string? namespace))
      (u/display-not-valid :typography-plugin-data-namespace namespace)

      (not (string? key))
      (u/display-not-valid :typography-plugin-data-key key)

      (and (some? value) (not (string? value)))
      (u/display-not-valid :typography-plugin-data value)

      :else
      (st/emit! (dw/set-plugin-data $file :typography $id (keyword "shared" namespace) key value))))

  (getSharedPluginDataKeys
    [self namespace]
    (cond
      (not (string? namespace))
      (u/display-not-valid :typography-plugin-data-namespace namespace)

      :else
      (let [typography (u/proxy->library-typography self)]
        (apply array (keys (dm/get-in typography [:plugin-data (keyword "shared" namespace)])))))))

(defn lib-typography-proxy
  [plugin-id file-id id]
  (assert (uuid? file-id))
  (assert (uuid? id))

  (cr/add-properties!
   (LibraryTypographyProxy. plugin-id file-id id)
   {:name "$plugin" :enumerable false :get (constantly plugin-id)}
   {:name "$id" :enumerable false :get (constantly id)}
   {:name "$file" :enumerable false :get (constantly file-id)}
   {:name "id" :get (fn [_] (dm/str id))}

   {:name "name"
    :get #(-> % u/proxy->library-typography :name)
    :set
    (fn [self value]
      (if (and (some? value) (string? value))
        (let [typo (u/proxy->library-typography self)
              value (dm/str (d/nilv (:path typo) "") " / " value)]
          (st/emit! (dwl/rename-typography file-id id value)))
        (u/display-not-valid :library-typography-name value)))}

   {:name "path"
    :get #(-> % u/proxy->library-typography :path)
    :set
    (fn [self value]
      (if (and (some? value) (string? value))
        (let [typo (-> (u/proxy->library-typography self)
                       (update :name #(str value " / " %)))]
          (st/emit! (dwl/update-typography typo file-id)))
        (u/display-not-valid :library-typography-path value)))}

   {:name "fontId"
    :get #(-> % u/proxy->library-typography :font-id)
    :set
    (fn [self value]
      (if (and (some? value) (string? value))
        (let [typo (-> (u/proxy->library-typography self)
                       (assoc :font-id value))]
          (st/emit! (dwl/update-typography typo file-id)))
        (u/display-not-valid :library-typography-font-id value)))}

   {:name "fontFamily"
    :get #(-> % u/proxy->library-typography :font-family)
    :set
    (fn [self value]
      (if (and (some? value) (string? value))
        (let [typo (-> (u/proxy->library-typography self)
                       (assoc :font-family value))]
          (st/emit! (dwl/update-typography typo file-id)))
        (u/display-not-valid :library-typography-font-family value)))}

   {:name "fontVariantId"
    :get #(-> % u/proxy->library-typography :font-variant-id)
    :set
    (fn [self value]
      (if (and (some? value) (string? value))
        (let [typo (-> (u/proxy->library-typography self)
                       (assoc :font-variant-id value))]
          (st/emit! (dwl/update-typography typo file-id)))
        (u/display-not-valid :library-typography-font-variant-id value)))}

   {:name "fontSize"
    :get #(-> % u/proxy->library-typography :font-size)
    :set
    (fn [self value]
      (if (and (some? value) (string? value))
        (let [typo (-> (u/proxy->library-typography self)
                       (assoc :font-size value))]
          (st/emit! (dwl/update-typography typo file-id)))
        (u/display-not-valid :library-typography-font-size value)))}

   {:name "fontWeight"
    :get #(-> % u/proxy->library-typography :font-weight)
    :set
    (fn [self value]
      (if (and (some? value) (string? value))
        (let [typo (-> (u/proxy->library-typography self)
                       (assoc :font-weight value))]
          (st/emit! (dwl/update-typography typo file-id)))
        (u/display-not-valid :library-typography-font-weight value)))}

   {:name "fontStyle"
    :get #(-> % u/proxy->library-typography :font-style)
    :set
    (fn [self value]
      (if (and (some? value) (string? value))
        (let [typo (-> (u/proxy->library-typography self)
                       (assoc :font-style value))]
          (st/emit! (dwl/update-typography typo file-id)))
        (u/display-not-valid :library-typography-font-style value)))}

   {:name "lineHeight"
    :get #(-> % u/proxy->library-typography :font-height)
    :set
    (fn [self value]
      (if (and (some? value) (string? value))
        (let [typo (-> (u/proxy->library-typography self)
                       (assoc :font-height value))]
          (st/emit! (dwl/update-typography typo file-id)))
        (u/display-not-valid :library-typography-font-height value)))}

   {:name "letterSpacing"
    :get #(-> % u/proxy->library-typography :letter-spacing)
    :set
    (fn [self value]
      (if (and (some? value) (string? value))
        (let [typo (-> (u/proxy->library-typography self)
                       (assoc :letter-spacing value))]
          (st/emit! (dwl/update-typography typo file-id)))
        (u/display-not-valid :library-typography-letter-spacing value)))}

   {:name "textTransform"
    :get #(-> % u/proxy->library-typography :text-transform)
    :set
    (fn [self value]
      (if (and (some? value) (string? value))
        (let [typo (-> (u/proxy->library-typography self)
                       (assoc :text-transform value))]
          (st/emit! (dwl/update-typography typo file-id)))
        (u/display-not-valid :library-typography-text-transform value)))}))

(deftype LibraryComponentProxy [$plugin $file $id]
  Object

  (remove
    [_]
    (st/emit! (dwl/delete-component {:id $id})))

  (instance
    [_]
    (let [id-ref (atom nil)]
      (st/emit! (dwl/instantiate-component $file $id (gpt/point 0 0) {:id-ref id-ref}))
      (shapes/shape-proxy $plugin @id-ref)))

  (getPluginData
    [self key]
    (cond
      (not (string? key))
      (u/display-not-valid :component-plugin-data-key key)

      :else
      (let [component (u/proxy->library-component self)]
        (dm/get-in component [:plugin-data (keyword "plugin" (str $plugin)) key]))))

  (setPluginData
    [_ key value]
    (cond
      (not= $file (:current-file-id @st/state))
      (u/display-not-valid :component-edit-non-local-library $file)

      (not (string? key))
      (u/display-not-valid :component-plugin-data-key key)

      (and (some? value) (not (string? value)))
      (u/display-not-valid :component-plugin-data value)

      :else
      (st/emit! (dw/set-plugin-data $file :component $id (keyword "plugin" (str $plugin)) key value))))

  (getPluginDataKeys
    [self]
    (let [component (u/proxy->library-component self)]
      (apply array (keys (dm/get-in component [:plugin-data (keyword "plugin" (str $plugin))])))))

  (getSharedPluginData
    [self namespace key]
    (cond
      (not (string? namespace))
      (u/display-not-valid :component-plugin-data-namespace namespace)

      (not (string? key))
      (u/display-not-valid :component-plugin-data-key key)

      :else
      (let [component (u/proxy->library-component self)]
        (dm/get-in component [:plugin-data (keyword "shared" namespace) key]))))

  (setSharedPluginData
    [_ namespace key value]
    (cond
      (not= $file (:current-file-id @st/state))
      (u/display-not-valid :component-edit-non-local-library $file)

      (not (string? namespace))
      (u/display-not-valid :component-plugin-data-namespace namespace)

      (not (string? key))
      (u/display-not-valid :component-plugin-data-key key)

      (and (some? value) (not (string? value)))
      (u/display-not-valid :component-plugin-data value)

      :else
      (st/emit! (dw/set-plugin-data $file :component $id (keyword "shared" namespace) key value))))

  (getSharedPluginDataKeys
    [self namespace]
    (cond
      (not (string? namespace))
      (u/display-not-valid :component-plugin-data-namespace namespace)

      :else
      (let [component (u/proxy->library-component self)]
        (apply array (keys (dm/get-in component [:plugin-data (keyword "shared" namespace)])))))))

(defn lib-component-proxy
  [plugin-id file-id id]
  (assert (uuid? file-id))
  (assert (uuid? id))

  (cr/add-properties!
   (LibraryComponentProxy. plugin-id file-id id)
   {:name "$plugin" :enumerable false :get (constantly plugin-id)}
   {:name "$id" :enumerable false :get (constantly id)}
   {:name "$file" :enumerable false :get (constantly file-id)}
   {:name "id" :get (fn [_] (dm/str id))}

   {:name "name"
    :get #(-> % u/proxy->library-component :name)
    :set
    (fn [self value]
      (if (and (some? value) (string? value))
        (let [component (u/proxy->library-component self)
              value (dm/str (d/nilv (:path component) "") " / " value)]
          (st/emit! (dwl/rename-component id value)))
        (u/display-not-valid :library-component-name value)))}

   {:name "path"
    :get #(-> % u/proxy->library-component :path)
    :set
    (fn [self value]
      (if (and (some? value) (string? value))
        (let [component (u/proxy->library-component self)
              value (dm/str value " / " (:name component))]
          (st/emit! (dwl/rename-component id value)))
        (u/display-not-valid :library-component-path value)))}))

(deftype Library [$plugin $id]
  Object

  (createColor
    [_]
    (let [color-id (uuid/next)]
      (st/emit! (dwl/add-color {:id color-id :name "Color" :color "#000000" :opacity 1} {:rename? false}))
      (lib-color-proxy $plugin $id color-id)))

  (createTypography
    [_]
    (let [typography-id (uuid/next)]
      (st/emit! (dwl/add-typography (ctt/make-typography {:id typography-id :name "Typography"}) false))
      (lib-typography-proxy $plugin $id typography-id)))

  (createComponent
    [_ shapes]
    (let [id-ref (atom nil)
          ids (into #{} (map #(obj/get % "$id")) shapes)]
      (st/emit! (dwl/add-component id-ref ids))
      (lib-component-proxy $plugin $id @id-ref)))

  ;; Plugin data
  (getPluginData
    [self key]
    (cond
      (not (string? key))
      (u/display-not-valid :file-plugin-data-key key)

      :else
      (let [file (u/proxy->file self)]
        (dm/get-in file [:data :plugin-data (keyword "plugin" (str $plugin)) key]))))

  (setPluginData
    [_ key value]
    (cond
      (not (string? key))
      (u/display-not-valid :file-plugin-data-key key)

      (and (some? value) (not (string? value)))
      (u/display-not-valid :file-plugin-data value)

      :else
      (st/emit! (dw/set-plugin-data $id :file (keyword "plugin" (str $plugin)) key value))))

  (getPluginDataKeys
    [self]
    (let [file (u/proxy->file self)]
      (apply array (keys (dm/get-in file [:data :plugin-data (keyword "plugin" (str $plugin))])))))

  (getSharedPluginData
    [self namespace key]
    (cond
      (not (string? namespace))
      (u/display-not-valid :file-plugin-data-namespace namespace)

      (not (string? key))
      (u/display-not-valid :file-plugin-data-key key)

      :else
      (let [file (u/proxy->file self)]
        (dm/get-in file [:data :plugin-data (keyword "shared" namespace) key]))))

  (setSharedPluginData
    [_ namespace key value]

    (cond
      (not (string? namespace))
      (u/display-not-valid :file-plugin-data-namespace namespace)

      (not (string? key))
      (u/display-not-valid :file-plugin-data-key key)

      (and (some? value) (not (string? value)))
      (u/display-not-valid :file-plugin-data value)

      :else
      (st/emit! (dw/set-plugin-data $id :file (keyword "shared" namespace) key value))))

  (getSharedPluginDataKeys
    [self namespace]
    (cond
      (not (string? namespace))
      (u/display-not-valid :file-plugin-data-namespace namespace)

      :else
      (let [file (u/proxy->file self)]
        (apply array (keys (dm/get-in file [:data :plugin-data (keyword "shared" namespace)])))))))

(defn library-proxy
  [plugin-id file-id]
  (assert (uuid? file-id) "File id not valid")

  (cr/add-properties!
   (Library. plugin-id file-id)
   {:name "$plugin" :enumerable false :get (constantly plugin-id)}
   {:name "$file" :enumerable false :get (constantly file-id)}

   {:name "id"
    :get #(-> % u/proxy->file :id str)}

   {:name "name"
    :get #(-> % u/proxy->file :name)}

   {:name "colors"
    :get
    (fn [_]
      (let [file (u/locate-file file-id)
            colors (->> file :data :colors keys (map #(lib-color-proxy plugin-id file-id %)))]
        (apply array colors)))}

   {:name "typographies"
    :get
    (fn [_]
      (let [file (u/locate-file file-id)
            typographies (->> file :data :typographies keys (map #(lib-typography-proxy plugin-id file-id %)))]
        (apply array typographies)))}

   {:name "components"
    :get
    (fn [_]
      (let [file (u/locate-file file-id)
            components (->> file
                            :data
                            :components
                            (remove (comp :deleted second))
                            (map first)
                            (map #(lib-component-proxy plugin-id file-id %)))]
        (apply array components)))}))

(deftype PenpotLibrarySubcontext [$plugin]
  Object
  (find
    [_ _name])

  (find [_]))

(defn library-subcontext
  [plugin-id]
  (cr/add-properties!
   (PenpotLibrarySubcontext. plugin-id)
   {:name "$plugin" :enumerable false :get (constantly plugin-id)}
   {:name "local" :get
    (fn [_]
      (library-proxy plugin-id (:current-file-id @st/state)))}

   {:name "connected" :get
    (fn [_]
      (let [libraries (get @st/state :workspace-libraries)]
        (apply array (->> libraries keys (map (partial library-proxy plugin-id))))))}))
