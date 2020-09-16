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
   [app.common.math :as math]
   [app.util.dom :as dom]
   [app.util.data :refer [classnames]]
   [app.util.i18n :as i18n :refer [tr]]
   [app.main.ui.modal :as modal]
   [app.common.data :as d]
   [app.main.refs :as refs]))

(defn color-picker-callback
  [color handle-change-color handle-open handle-close disable-opacity]
  (fn [event]
    (let [x (.-clientX event)
          y (.-clientY event)
          props {:x x
                 :y y
                 :on-change handle-change-color
                 :on-close handle-close
                 :value (:value color)
                 :opacity (:opacity color)
                 :disable-opacity disable-opacity}]
      (handle-open)
      (modal/show! :colorpicker props))))

(defn value-to-background [value]
  (if (= value :multiple) "transparent" value))

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
  [{:keys [color on-change on-open on-close disable-opacity]}]
  (let [;;
        file-colors (mf/deref refs/workspace-file-colors)
        shared-libs (mf/deref refs/workspace-libraries)

        get-color-name (fn [{:keys [id file-id]}]
                         (let [src-colors (if file-id (get-in shared-libs [file-id :data :colors]) file-colors)]
                           (get-in src-colors [id :name])))
        ;;

        default-color {:value "#000000" :opacity 1}

        parse-color (fn [color]
                      (-> (merge default-color color)
                          (update :value #(or % "#000000"))
                          (update :opacity #(or % 1))))

        state (mf/use-state (parse-color color))

        value (:value @state)
        opacity (:opacity @state)

        change-value (fn [new-value]
                       (swap! state assoc :value new-value)
                       (when on-change (on-change new-value (remove-multiple opacity))))

        change-opacity (fn [new-opacity]
                         (swap! state assoc :opacity new-opacity)
                         (when on-change (on-change (remove-multiple value) new-opacity)))

        handle-pick-color (fn [new-value new-opacity id file-id]
                            (reset! state {:value new-value :opacity new-opacity})
                            (when on-change (on-change new-value new-opacity id file-id)))

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
        ;; is this necessary?

    [:div.row-flex.color-data
     [:span.color-th
      {:class (when (:id color) "color-name")
       :style {:background-color (-> value value-to-background)}
       :on-click (color-picker-callback @state handle-pick-color handle-open handle-close disable-opacity)}
      (when (= value :multiple) "?")]

     (if (:id color)
       [:div.color-info
        [:div.color-name (str (get-color-name color))]]
       [:div.color-info
        [:input {:value (-> value remove-hash)
                 :pattern "^[0-9a-fA-F]{0,6}$"
                 :placeholder (tr "settings.multiple")
                 :on-click select-all
                 :on-change handle-value-change}]])

     (when (not disable-opacity)
       [:div.input-element
        {:class (classnames :percentail (not= opacity :multiple))}
        [:input.input-text {:type "number"
                            :value (-> opacity opacity->string)
                            :placeholder (tr "settings.multiple")
                            :on-click select-all
                            :on-change handle-opacity-change
                            :min "0"
                            :max "100"}]])]))

