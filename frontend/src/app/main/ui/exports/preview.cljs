;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS SUBSIDIARY SL

(ns app.main.ui.exports.preview
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.files.helpers :as cfh]
   [app.common.geom.shapes.bounds :as gsb]
   [app.common.logging :as log]
   [app.main.data.exports.assets :as de]
   [app.main.data.exports.wasm :as wasm.exports]
   [app.main.features :as features]
   [app.main.refs :as refs]
   [app.main.render :as render]
   [app.main.ui.components.title-bar :refer [title-bar*]]
   [app.main.ui.ds.buttons.button :refer [button*]]
   [app.main.ui.ds.controls.select :refer [select*]]
   [app.main.ui.hooks :as hooks]
   [app.render-wasm.api :as wasm.api]
   [app.util.i18n :refer [tr]]
   [app.util.strings :as ust]
   [app.util.timers :as timers]
   [app.util.webapi :as wapi]
   [cuerdas.core :as str]
   [okulary.core :as l]
   [rumext.v2 :as mf]))

;; Keep in sync with --export-preview-size in preview.scss.
(def ^:private preview-size 240)

(defn preview-settings
  "Keep raster previews small without changing the displayed export dimensions.
  SVG and PDF use a raster approximation at their natural size."
  [{:keys [width height]} {:keys [type scale]}]
  (let [raster? (contains? de/raster-export-types type)
        scale   (if raster? (or scale 1) 1)]
    {:type (if raster? type :png)
     :scale (min scale (/ preview-size (max 1 width height)))
     :width (js/Math.ceil (* width scale))
     :height (js/Math.ceil (* height scale))}))

(defn preview-bounds
  "Use the same bounds as the active export renderer."
  [objects object wasm?]
  (or (when (and wasm? (wasm.api/initialized?))
        (wasm.api/get-shape-extrect (:id object)))
      (let [bounds (gsb/get-object-bounds objects object {:ignore-margin? false})
            rounded-size #(js/parseFloat (ust/format-precision % render/viewbox-decimal-precision))]
        (-> bounds
            (update :width rounded-size)
            (update :height rounded-size)))))

(mf/defc wasm-preview*
  {::mf/private true}
  [{:keys [objects object-id export bounds]}]
  (let [image*   (mf/use-state nil)
        image    @image*
        retry*   (mf/use-state 0)
        retry    @retry*
        width    (:width bounds)
        height   (:height bounds)
        retry-fn (mf/use-fn #(swap! retry* inc))]
    ;; Keep the current image alive while the next render is debounced.
    ;; Release its URI only after replacement or when the preview unmounts.
    (mf/use-effect
     (mf/deps (:uri image))
     (fn []
       (when-let [uri (:uri image)]
         #(wapi/revoke-uri uri))))
    (mf/use-effect
     (mf/deps objects object-id export width height retry)
     (fn []
       (let [task (timers/schedule
                   200
                   (fn []
                     (try
                       (if (wasm.api/initialized?)
                         (let [settings (preview-settings {:width width :height height} export)
                               uri      (wasm.exports/export-image-uri
                                         (assoc settings :object-id object-id))]
                           (reset! image* {:uri uri}))
                         (reset! image* {:error true}))
                       (catch :default cause
                         (log/error :hint "export preview failed" :cause cause)
                         (reset! image* {:error true})))))]
         #(timers/dispose! task))))
    (cond
      (:uri image)
      [:img {:class (stl/css :image)
             :src (:uri image)
             :alt (tr "workspace.options.export.preview-image")}]

      (:error image)
      [:div {:class (stl/css :status) :role "status"}
       [:span (tr "workspace.options.export.preview-error")]
       [:> button* {:variant "secondary" :on-click retry-fn} (tr "labels.retry")]]

      :else
      [:span {:class (stl/css :status) :role "status"} (tr "labels.loading")])))

(mf/defc preview-content*
  {::mf/private true}
  [{:keys [object-id exports from page-id]}]
  (let [index*      (mf/use-state 0)
        index       (min @index* (dec (count exports)))
        export      (nth exports index)
        options     (mf/with-memo [exports]
                      (mapv (fn [index export]
                              {:id (str index)
                               :label (str (inc index) ". " (str/upper (name (:type export)))
                                           (when (contains? de/raster-export-types (:type export))
                                             (str " · " (:scale export) "x"))
                                           (when (seq (:suffix export)) (str " · " (:suffix export))))})
                            (range (count exports)) exports))
        wasm-enabled (features/use-feature "render-wasm/v1")
        wasm?       (and (= from :workspace) wasm-enabled)
        objects-ref (mf/with-memo [object-id from page-id]
                      (l/derived
                       (fn [source]
                         (let [objects (if (= from :viewer)
                                         (get-in source [:pages page-id :objects])
                                         source)]
                           (select-keys objects
                                        (cons object-id (cfh/get-children-ids objects object-id)))))
                       (if (= from :viewer) refs/viewer-data refs/workspace-page-objects)
                       =))
        objects     (mf/deref objects-ref)
        object      (get objects object-id)
        bounds      (mf/with-memo [objects object wasm?]
                      (when object
                        (preview-bounds objects object wasm?)))
        on-change   (mf/use-fn
                     (fn [value]
                       (reset! index* (js/parseInt value 10))))]
    (when object
      (let [settings (preview-settings bounds export)]
        [:div {:class (stl/css :content)}
         (when (> (count exports) 1)
           [:> select* {:aria-label (tr "workspace.options.export.preview-preset")
                        :default-selected (str index)
                        :options options
                        :on-change on-change}])
         [:div {:class (stl/css-case :canvas true :opaque (= :jpeg (:type export)))
                :style {"--export-preview-ratio" (/ (max 1 (:width bounds))
                                                    (max 1 (:height bounds)))}}
          (if wasm?
            [:> wasm-preview* {:objects objects
                               :object-id object-id
                               :export export
                               :bounds bounds}]
            [:div {:class (stl/css :svg)
                   :role "img"
                   :aria-label (tr "workspace.options.export.preview-image")}
             [:& render/object-svg {:objects objects
                                    :object-id object-id
                                    :class (stl/css :svg-image)}]])]
         [:div {:class (stl/css :dimensions)}
          (str (:width settings) " × " (:height settings) " · " (str/upper (name (:type export))))]]))))

(mf/defc export-preview*
  [{:keys [object-id exports from page-id] :or {from :workspace}}]
  (let [open*     (hooks/use-persisted-state ::open false)
        open      @open*
        panel-id  (str "export-preview-" object-id)
        toggle-fn (mf/use-fn #(swap! open* not))]
    [:div {:class (stl/css :preview)}
     [:> title-bar* {:title (tr "workspace.options.export.preview")
                     :collapsable true
                     :collapsed (not open)
                     :on-collapsed toggle-fn
                     :aria-expanded open
                     :aria-controls panel-id}]
     (when open
       [:div {:id panel-id}
        [:> preview-content* {:object-id object-id :exports exports :from from :page-id page-id}]])]))
