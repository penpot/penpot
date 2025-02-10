;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.tokens.changes
  (:require
   [app.common.types.shape.layout :as ctsl]
   [app.common.types.shape.radius :as ctsr]
   [app.common.types.token :as ctt]
   [app.common.types.tokens-lib :as ctob]
   [app.main.data.event :as ev]
   [app.main.data.helpers :as dsh]
   [app.main.data.workspace :as udw]
   [app.main.data.workspace.colors :as wdc]
   [app.main.data.workspace.shape-layout :as dwsl]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.data.workspace.transforms :as dwt]
   [app.main.data.workspace.undo :as dwu]
   [app.main.store :as st]
   [app.main.ui.workspace.tokens.style-dictionary :as sd]
   [app.main.ui.workspace.tokens.tinycolor :as tinycolor]
   [app.main.ui.workspace.tokens.token :as wtt]
   [beicon.v2.core :as rx]
   [clojure.set :as set]
   [potok.v2.core :as ptk]))

;; Token Updates ---------------------------------------------------------------

(defn apply-token
  "Apply `attributes` that match `token` for `shape-ids`.

  Optionally remove attributes from `attributes-to-remove`,
  this is useful for applying a single attribute from an attributes set
  while removing other applied tokens from this set."
  [{:keys [attributes attributes-to-remove token shape-ids on-update-shape] :as _props}]
  (ptk/reify ::apply-token
    ptk/WatchEvent
    (watch [_ state _]
      (when-let [tokens (some-> (dsh/lookup-file-data state)
                                (get :tokens-lib)
                                (ctob/get-active-themes-set-tokens))]
        (->> (rx/from (sd/resolve-tokens+ tokens))
             (rx/mapcat
              (fn [resolved-tokens]
                (let [undo-id (js/Symbol)
                      resolved-value (get-in resolved-tokens [(wtt/token-identifier token) :resolved-value])
                      tokenized-attributes (wtt/attributes-map attributes token)]
                  (rx/of
                   (st/emit! (ptk/event ::ev/event {::ev/name "apply-tokens"}))
                   (dwu/start-undo-transaction undo-id)
                   (dwsh/update-shapes shape-ids (fn [shape]
                                                   (cond-> shape
                                                     attributes-to-remove
                                                     (update :applied-tokens #(apply (partial dissoc %) attributes-to-remove))
                                                     :always
                                                     (update :applied-tokens merge tokenized-attributes))))
                   (when on-update-shape
                     (on-update-shape resolved-value shape-ids attributes))
                   (dwu/commit-undo-transaction undo-id))))))))))

(defn unapply-token
  "Removes `attributes` that match `token` for `shape-ids`.

  Doesn't update shape attributes."
  [{:keys [attributes token shape-ids] :as _props}]
  (ptk/reify ::unapply-token
    ptk/WatchEvent
    (watch [_ _ _]
      (rx/of
       (let [remove-token #(when % (wtt/remove-attributes-for-token attributes token %))]
         (dwsh/update-shapes
          shape-ids
          (fn [shape]
            (update shape :applied-tokens remove-token))))))))

(defn toggle-token
  [{:keys [token-type-props token shapes] :as _props}]
  (ptk/reify ::on-toggle-token
    ptk/WatchEvent
    (watch [_ _ _]
      (let [{:keys [attributes all-attributes on-update-shape]} token-type-props
            unapply-tokens? (wtt/shapes-token-applied? token shapes (or all-attributes attributes))
            shape-ids (map :id shapes)]
        (if unapply-tokens?
          (rx/of
           (unapply-token {:attributes (or all-attributes attributes)
                           :token token
                           :shape-ids shape-ids}))
          (rx/of
           (apply-token {:attributes attributes
                         :token token
                         :shape-ids shape-ids
                         :on-update-shape on-update-shape})))))))

;; Shape Updates ---------------------------------------------------------------

(defn update-shape-radius-all
  ([value shape-ids attributes] (update-shape-radius-all value shape-ids attributes nil))
  ([value shape-ids _attributes page-id] ; The attributes param is needed to have the same arity that other update functions
   (dwsh/update-shapes shape-ids
                       (fn [shape]
                         (ctsr/set-radius-to-all-corners shape value))
                       {:reg-objects? true
                        :ignore-touched true
                        :page-id page-id
                        :attrs ctt/border-radius-keys})))

(defn update-shape-radius-for-corners
  ([value shape-ids attributes] (update-shape-radius-for-corners value shape-ids attributes nil))
  ([value shape-ids attributes page-id]
   (dwsh/update-shapes shape-ids
                       (fn [shape]
                         (ctsr/set-radius-for-corners shape attributes value))
                       {:reg-objects? true
                        :ignore-touched true
                        :page-id page-id
                        :attrs ctt/border-radius-keys})))

(defn update-opacity
  ([value shape-ids attributes] (update-opacity value shape-ids attributes nil))
  ([value shape-ids _attributes page-id] ; The attributes param is needed to have the same arity that other update functions
   (when (<= 0 value 1)
     (dwsh/update-shapes shape-ids
                         #(assoc % :opacity value)
                         {:ignore-touched true
                          :page-id page-id}))))

;; FIXME: if attributes are not always present, maybe we have an
;; options here for pass optional value and preserve the correct and
;; uniform api across all functions (?)

(defn update-rotation
  ([value shape-ids attributes] (update-rotation value shape-ids attributes nil))
  ([value shape-ids _attributes page-id] ; The attributes param is needed to have the same arity that other update functions
   (ptk/reify ::update-shape-rotation
     ptk/WatchEvent
     (watch [_ _ _]
       (rx/of
        (udw/trigger-bounding-box-cloaking shape-ids)
        (udw/increase-rotation shape-ids value nil
                               {:page-id page-id
                                :ignore-touched true}))))))

(defn update-stroke-width
  ([value shape-ids attributes] (update-stroke-width value shape-ids attributes nil))
  ([value shape-ids _attributes page-id] ; The attributes param is needed to have the same arity that other update functions
   (dwsh/update-shapes shape-ids
                       (fn [shape]
                         (when (seq (:strokes shape))
                           (assoc-in shape [:strokes 0 :stroke-width] value)))
                       {:reg-objects? true
                        :ignore-touched true
                        :page-id page-id
                        :attrs [:strokes]})))

(defn update-color [f value shape-ids page-id]
  (when-let [tc (tinycolor/valid-color value)]
    (let [hex (tinycolor/->hex-string tc)
          opacity (tinycolor/alpha tc)]
      (f shape-ids {:color hex :opacity opacity} 0 {:ignore-touched true
                                                    :page-id page-id}))))

(defn update-fill
  ([value shape-ids attributes] (update-fill value shape-ids attributes nil))
  ([value shape-ids _attributes page-id] ; The attributes param is needed to have the same arity that other update functions
   (update-color wdc/change-fill value shape-ids page-id)))

(defn update-stroke-color
  ([value shape-ids attributes] (update-stroke-color value shape-ids attributes nil))
  ([value shape-ids _attributes page-id] ; The attributes param is needed to have the same arity that other update functions
   (update-color wdc/change-stroke-color value shape-ids page-id)))

(defn update-fill-stroke
  ([value shape-ids attributes] (update-fill-stroke value shape-ids attributes nil))
  ([value shape-ids attributes page-id]
   (ptk/reify ::update-fill-stroke
     ptk/WatchEvent
     (watch [_ _ _]
       (rx/of
        (when (:fill attributes) (update-fill value shape-ids attributes page-id))
        (when (:stroke-color attributes) (update-stroke-color value shape-ids attributes page-id)))))))

(defn update-shape-dimensions
  ([value shape-ids attributes] (update-shape-dimensions value shape-ids attributes nil))
  ([value shape-ids attributes page-id]
   (ptk/reify ::update-shape-dimensions
     ptk/WatchEvent
     (watch [_ _ _]
       (rx/of
        (when (:width attributes) (dwt/update-dimensions shape-ids :width value {:ignore-touched true :page-id page-id}))
        (when (:height attributes) (dwt/update-dimensions shape-ids :height value {:ignore-touched true :page-id page-id})))))))

(defn- attributes->layout-gap [attributes value]
  (let [layout-gap (-> (set/intersection attributes #{:column-gap :row-gap})
                       (zipmap (repeat value)))]
    {:layout-gap layout-gap}))

(defn- shape-ids-with-layout [state page-id shape-ids]
  (->> (dsh/lookup-shapes state page-id shape-ids)
       (eduction
        (filter ctsl/any-layout?)
        (map :id))))

(defn- shape-ids-with-layout-parent [state page-id shape-ids]
  (let [objects (dsh/lookup-page-objects state)]
    (->> (dsh/lookup-shapes state page-id shape-ids)
         (eduction
          (filter #(ctsl/any-layout-immediate-child? objects %))
          (map :id)))))

(defn update-layout-item-margin
  ([value shape-ids attrs] (update-layout-item-margin value shape-ids attrs nil))
  ([value shape-ids attrs page-id]
   (ptk/reify ::update-layout-item-margin
     ptk/WatchEvent
     (watch [_ state _]
       (let [ids-with-layout-parent (shape-ids-with-layout-parent state (or page-id (:current-page-id state)) shape-ids)]
         (rx/of
          (dwsl/update-layout ids-with-layout-parent
                              {:layout-item-margin (zipmap attrs (repeat value))}
                              {:ignore-touched true
                               :page-id page-id})))))))

(defn update-layout-padding
  ([value shape-ids attrs] (update-layout-padding value shape-ids attrs nil))
  ([value shape-ids attrs page-id]
   (ptk/reify ::update-layout-padding
     ptk/WatchEvent
     (watch [_ state _]
       (let [ids-with-layout (shape-ids-with-layout state (or page-id (:current-page-id state)) shape-ids)]
         (rx/of
          (dwsl/update-layout ids-with-layout
                              {:layout-padding (zipmap attrs (repeat value))}
                              {:ignore-touched true
                               :page-id page-id})))))))

(defn update-layout-spacing
  ([value shape-ids attributes] (update-layout-spacing value shape-ids attributes nil))
  ([value shape-ids attributes page-id]
   (ptk/reify ::update-layout-spacing
     ptk/WatchEvent
     (watch [_ state _]
       (let [ids-with-layout (shape-ids-with-layout state (or page-id (:current-page-id state)) shape-ids)
             layout-attributes (attributes->layout-gap attributes value)]
         (rx/of
          (dwsl/update-layout ids-with-layout
                              layout-attributes
                              {:ignore-touched true
                               :page-id page-id})))))))

(defn update-shape-position
  ([value shape-ids attributes] (update-shape-position value shape-ids attributes nil))
  ([value shape-ids attributes page-id]
   (ptk/reify ::update-shape-position
     ptk/WatchEvent
     (watch [_ _ _]
       (rx/concat
        (map #(dwt/update-position % (zipmap attributes (repeat value)) {:ignore-touched true :page-id page-id}) shape-ids))))))

(defn update-layout-sizing-limits
  ([value shape-ids attributes] (update-layout-sizing-limits value shape-ids attributes nil))
  ([value shape-ids attributes page-id]
   (ptk/reify ::update-layout-sizing-limits
     ptk/WatchEvent
     (watch [_ _ _]
       (let [props (-> {:layout-item-min-w value
                        :layout-item-min-h value
                        :layout-item-max-w value
                        :layout-item-max-h value}
                       (select-keys attributes))]
         (dwsl/update-layout-child shape-ids props {:ignore-touched true
                                                    :page-id page-id}))))))
