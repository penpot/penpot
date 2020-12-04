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
   [app.common.pages :as cp]
   [app.common.data :as d]
   [app.util.dom :as dom]
   [app.util.data :refer [classnames]]
   [app.util.i18n :as i18n :refer [tr]]
   [app.util.color :as uc]
   [app.main.refs :as refs]
   [app.main.data.modal :as modal]
   [app.main.ui.hooks :as h]
   [app.main.ui.context :as ctx]
   [app.main.ui.components.color-bullet :as cb]
   [app.main.ui.components.numeric-input :refer [numeric-input]]))

(defn color-picker-callback
  [color disable-gradient disable-opacity handle-change-color handle-open handle-close]
  (fn [event]
    (let [x (.-clientX event)
          y (.-clientY event)
          props {:x x
                 :y y
                 :disable-gradient disable-gradient
                 :disable-opacity disable-opacity
                 :on-change handle-change-color
                 :on-close handle-close
                 :data color}]
      (handle-open)
      (modal/show! :colorpicker props))))


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
  [{:keys [color disable-gradient disable-opacity on-change on-open on-close]}]
  (let [current-file-id (mf/use-ctx ctx/current-file-id)
        file-colors     (mf/deref refs/workspace-file-colors)
        shared-libs     (mf/deref refs/workspace-libraries)

        get-color-name (fn [{:keys [id file-id]}]
                         (let [src-colors (if (= file-id current-file-id)
                                            file-colors
                                            (get-in shared-libs [file-id :data :colors]))]
                           (get-in src-colors [id :name])))

        parse-color (fn [color]
                      (-> color
                          (update :color #(or % (:value color)))))

        change-value (fn [new-value]
                       (when on-change (on-change (-> color
                                                      (assoc :color new-value)
                                                      (dissoc :gradient)))))

        change-opacity (fn [new-opacity]
                         (when on-change (on-change (assoc color :opacity new-opacity))))

        handle-pick-color (fn [color]
                            (when on-change (on-change color)))

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
                     (dom/select-text! (dom/get-target event)))

        handle-click-color (mf/use-callback
                            (mf/deps color)
                            (let [;; If multiple, we change to default color
                                  color (if (uc/multiple? color)
                                          {:color cp/default-color :opacity 1}
                                          color)]
                              (color-picker-callback color
                                                     disable-gradient
                                                     disable-opacity
                                                     handle-pick-color
                                                     handle-open
                                                     handle-close)))

        prev-color (h/use-previous color)]

    (mf/use-effect
     (mf/deps color prev-color)
     (fn []
       (when (not= prev-color color)
         (modal/update-props! :colorpicker {:data (parse-color color)}))))

    [:div.row-flex.color-data
     [:& cb/color-bullet {:color color
                          :on-click handle-click-color}]

     (cond
       ;; Rendering a color with ID
       (:id color)
       [:div.color-info
        [:div.color-name (str (get-color-name color))]]

       ;; Rendering a gradient
       (and (not (uc/multiple? color))
            (:gradient color) (get-in color [:gradient :type]))
       [:div.color-info
        [:div.color-name (cb/gradient-type->string (get-in color [:gradient :type]))]]

       ;; Rendering a plain color/opacity
       :else
       [:*
        [:div.color-info
         [:input {:value (if (uc/multiple? color)
                           ""
                           (-> color :color remove-hash))
                  :pattern "^[0-9a-fA-F]{0,6}$"
                  :placeholder (tr "settings.multiple")
                  :on-click select-all
                  :on-change handle-value-change}]]

        (when (and (not disable-opacity)
                   (not (:gradient color)))
          [:div.input-element
           {:class (classnames :percentail (not= (:opacity color) :multiple))}
           [:> numeric-input {:value (-> color :opacity opacity->string)
                              :placeholder (tr "settings.multiple")
                              :on-click select-all
                              :on-change handle-opacity-change
                              :min "0"
                              :max "100"}]])])]))

