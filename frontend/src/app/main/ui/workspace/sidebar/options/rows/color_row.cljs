;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.main.ui.workspace.sidebar.options.rows.color-row
  (:require
   [rumext.alpha :as mf]
   [cuerdas.core :as str]
   [app.common.math :as math]
   [app.util.dom :as dom]
   [app.util.data :refer [classnames]]
   [app.util.i18n :as i18n :refer [tr]]
   [app.main.data.modal :as modal]
   [app.common.data :as d]
   [app.main.refs :as refs]
   [app.util.color :as uc]))

(defn color-picker-callback
  [color handle-change-color handle-open handle-close]
  (fn [event]
    (let [x (.-clientX event)
          y (.-clientY event)
          props {:x x
                 :y y
                 :on-change handle-change-color
                 :on-close handle-close
                 :data color}]
      (handle-open)
      (modal/show! :colorpicker props))))


;; TODO: REMOVE `VALUE` WHEN COLOR IS INTEGRATED
(defn as-background [{:keys [color opacity gradient value] :as tt}]
  (cond
    (and gradient (not= :multiple gradient))
    (uc/gradient->css gradient)

    (not= color :multiple)
    (let [[r g b] (uc/hex->rgb (or color value))]
      (str/fmt "rgba(%s, %s, %s, %s)" r g b opacity))

    :else "transparent"))

(defn remove-hash [value]
  (if (or (nil? value) (= value :multiple)) "" (subs value 1)))

(defn append-hash [value]
  (str "#" value))

(defn opacity->string [opacity]
  (if (= opacity :multiple)
    ""
    (str (-> opacity
             (d/coalesce 1)
             (* 100)
             (math/round)))))

(defn string->opacity [opacity-str]
  (-> opacity-str
      (d/parse-integer 1)
      (/ 100)))

(defn remove-multiple [v]
  (if (= v :multiple) nil v))

(mf/defc color-row
  [{:keys [color on-change on-open on-close]}]
  (let [file-colors (mf/deref refs/workspace-file-colors)
        shared-libs (mf/deref refs/workspace-libraries)

        get-color-name (fn [{:keys [id file-id]}]
                         (let [src-colors (if file-id (get-in shared-libs [file-id :data :colors]) file-colors)]
                           (get-in src-colors [id :name])))

        parse-color (fn [color]
                      (-> color
                          (update :color #(or % (:value color)))))

        state (mf/use-state (parse-color color))

        value (:color @state)
        opacity (:opacity @state)

        change-value (fn [new-value]
                       (swap! state assoc :color new-value)
                       (when on-change (on-change new-value (remove-multiple opacity))))

        change-opacity (fn [new-opacity]
                         (swap! state assoc :opacity new-opacity)
                         (when on-change (on-change (remove-multiple value) new-opacity)))

        ;;handle-pick-color (fn [new-value new-opacity id file-id]
        ;;                    (reset! state {:color new-value :opacity new-opacity})
        ;;                    (when on-change (on-change new-value new-opacity id file-id)))

        handle-pick-color (fn [color]
                            (reset! state color)
                            (when on-change
                              (on-change color)))

        handle-open (fn [] (when on-open (on-open)))

        handle-close (fn [value opacity id file-id]
                       (when on-close (on-close value opacity id file-id)))

        handle-value-change (fn [event]
                              (let [target (dom/get-target event)]
                                (when (dom/valid? target)
                                  (-> target
                                      dom/get-value
                                      append-hash
                                      change-value))))

        handle-opacity-change (fn [event]
                                (let [target (dom/get-target event)]
                                  (when (dom/valid? target)
                                    (-> target
                                        dom/get-value
                                        string->opacity
                                        change-opacity))))

        select-all (fn [event]
                     (dom/select-text! (dom/get-target event)))]

    (mf/use-effect
     (mf/deps color)
     #(reset! state (parse-color color)))

    [:div.row-flex.color-data
     [:span.color-th
      {:class (when (and (:id color) (not= (:id color) :multiple)) "color-name")
       :style {:background (as-background color)}
       :on-click (color-picker-callback @state handle-pick-color handle-open handle-close)}
      (when (= value :multiple) "?")]

     (cond
       ;; Rendering a color with ID
       (:id color)
       [:div.color-info
        [:div.color-name (str (get-color-name color))]]

       ;; Rendering a gradient
       (:gradient color)
       [:div.color-info
        [:div.color-name (str (get-in color [:gradient :type]))]]

       ;; Rendering a plain color/opacity
       :else
       [:*
        [:div.color-info
         [:input {:value (-> value remove-hash)
                  :pattern "^[0-9a-fA-F]{0,6}$"
                  :placeholder (tr "settings.multiple")
                  :on-click select-all
                  :on-change handle-value-change}]]

        [:div.input-element
         {:class (classnames :percentail (not= opacity :multiple))}
         [:input.input-text {:type "number"
                             :value (-> opacity opacity->string)
                             :placeholder (tr "settings.multiple")
                             :on-click select-all
                             :on-change handle-opacity-change
                             :min "0"
                             :max "100"}]]])]))

