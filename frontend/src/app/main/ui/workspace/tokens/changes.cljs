;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.tokens.changes
  (:require
   [app.common.types.shape.radius :as ctsr]
   [app.common.types.token :as ctt]
   [app.common.types.tokens-lib :as ctob]
   [app.main.data.workspace :as udw]
   [app.main.data.workspace.colors :as wdc]
   [app.main.data.workspace.shape-layout :as dwsl]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.data.workspace.state-helpers :as wsh]
   [app.main.data.workspace.transforms :as dwt]
   [app.main.data.workspace.undo :as dwu]
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
      (when-let [tokens (some-> (get-in state [:workspace-data :tokens-lib])
                                (ctob/get-active-themes-set-tokens))]
        (->> (rx/from (sd/resolve-tokens+ tokens))
             (rx/mapcat
              (fn [resolved-tokens]
                (let [undo-id (js/Symbol)
                      resolved-value (get-in resolved-tokens [(wtt/token-identifier token) :resolved-value])
                      tokenized-attributes (wtt/attributes-map attributes token)]
                  (rx/of
                   (dwu/start-undo-transaction undo-id)
                   (dwsh/update-shapes shape-ids (fn [shape]
                                                   (cond-> shape
                                                     attributes-to-remove (update :applied-tokens #(apply (partial dissoc %) attributes-to-remove))
                                                     :always (update :applied-tokens merge tokenized-attributes))))
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

(defn update-shape-radius-all [value shape-ids]
  (dwsh/update-shapes shape-ids
                      (fn [shape]
                        (when (ctsr/can-get-border-radius? shape)
                          (ctsr/set-radius-to-all-corners shape value)))
                      {:reg-objects? true
                       :attrs ctt/border-radius-keys}))

(defn update-opacity [value shape-ids]
  (when (<= 0 value 1)
    (dwsh/update-shapes shape-ids #(assoc % :opacity value))))

(defn update-rotation [value shape-ids]
  (ptk/reify ::update-shape-rotation
    ptk/WatchEvent
    (watch [_ _ _]
      (rx/of
       (udw/trigger-bounding-box-cloaking shape-ids)
       (udw/increase-rotation shape-ids value)))))

(defn update-shape-radius-single-corner [value shape-ids attributes]
  (dwsh/update-shapes shape-ids
                      (fn [shape]
                        (when (ctsr/can-get-border-radius? shape)
                          (ctsr/set-radius-to-single-corner shape (first attributes) value)))
                      {:reg-objects? true
                       :attrs ctt/border-radius-keys}))

(defn update-stroke-width
  [value shape-ids]
  (dwsh/update-shapes shape-ids
                      (fn [shape]
                        (when (seq (:strokes shape))
                          (assoc-in shape [:strokes 0 :stroke-width] value)))
                      {:reg-objects? true
                       :attrs [:strokes]}))

(defn update-color [f value shape-ids]
  (let [color (some->> value
                       (tinycolor/valid-color)
                       (tinycolor/->hex)
                       (str "#"))]
    (f shape-ids {:color color} 0)))

(defn update-fill
  [value shape-ids]
  (update-color wdc/change-fill value shape-ids))

(defn update-stroke-color
  [value shape-ids]
  (update-color wdc/change-stroke value shape-ids))

(defn update-shape-dimensions [value shape-ids attributes]
  (ptk/reify ::update-shape-dimensions
    ptk/WatchEvent
    (watch [_ _ _]
      (rx/of
       (when (:width attributes) (dwt/update-dimensions shape-ids :width value))
       (when (:height attributes) (dwt/update-dimensions shape-ids :height value))))))

(defn- attributes->layout-gap [attributes value]
  (let [layout-gap (-> (set/intersection attributes #{:column-gap :row-gap})
                       (zipmap (repeat value)))]
    {:layout-gap layout-gap}))

(defn update-layout-padding [value shape-ids attrs]
  (dwsl/update-layout shape-ids {:layout-padding (zipmap attrs (repeat value))}))

(defn update-layout-spacing [value shape-ids attributes]
  (ptk/reify ::update-layout-spacing
    ptk/WatchEvent
    (watch [_ state _]
      (let [layout-shape-ids (->> (wsh/lookup-shapes state shape-ids)
                                  (eduction
                                   (filter :layout)
                                   (map :id)))
            layout-attributes (attributes->layout-gap attributes value)]
        (rx/of
         (dwsl/update-layout layout-shape-ids layout-attributes))))))

(defn update-shape-position [value shape-ids attributes]
  (ptk/reify ::update-shape-position
    ptk/WatchEvent
    (watch [_ _ _]
      (rx/concat
       (map #(dwt/update-position % (zipmap attributes (repeat value))) shape-ids)))))

(defn update-layout-sizing-limits [value shape-ids attributes]
  (ptk/reify ::update-layout-sizing-limits
    ptk/WatchEvent
    (watch [_ _ _]
      (let [props (-> {:layout-item-min-w value
                       :layout-item-min-h value
                       :layout-item-max-w value
                       :layout-item-max-h value}
                      (select-keys attributes))]
        (dwsl/update-layout-child shape-ids props)))))
