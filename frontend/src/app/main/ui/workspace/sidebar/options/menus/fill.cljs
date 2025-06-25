;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.sidebar.options.menus.fill
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.colors :as clr]
   [app.common.data :as d]
   [app.common.types.color :as ctc]
   [app.common.types.fill :as types.fill]
   [app.common.types.shape.attrs :refer [default-color]]
   [app.config :as cfg]
   [app.main.data.workspace.colors :as dc]
   [app.main.store :as st]
   [app.main.ui.components.title-bar :refer [title-bar]]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.hooks :as h]
   [app.main.ui.icons :as i]
   [app.main.ui.workspace.sidebar.options.rows.color-row :refer [color-row*]]
   [app.util.dom :as dom]
   [app.util.i18n :as i18n :refer [tr]]
   [rumext.v2 :as mf]))

;; FIXME:revisit this
(def fill-attrs
  [:fills
   :fill-color
   :fill-opacity
   :fill-color-ref-id
   :fill-color-ref-file
   :fill-color-gradient
   :hide-fill-on-export])

(def fill-attrs-shape
  (conj fill-attrs :hide-fill-on-export))

(defn color-values
  [color]
  {:color (:fill-color color)
   :opacity (:fill-opacity color)
   :id (:fill-color-ref-id color)
   :file-id (:fill-color-ref-file color)
   :gradient (:fill-color-gradient color)})

(mf/defc fill-menu
  {::mf/wrap [#(mf/memo' % (mf/check-props ["ids" "values"]))]}
  [{:keys [ids type values] :as props}]
  (let [label (case type
                :multiple (tr "workspace.options.selection-fill")
                :group (tr "workspace.options.group-fill")
                (tr "workspace.options.fill"))

        ;; Excluding nil values
        values               (d/without-nils values)
        fills                (if (contains? cfg/flags :frontend-binary-fills)
                               (take types.fill/MAX-FILLS (d/nilv (:fills values) []))
                               (:fills values))
        has-fills?           (or (= :multiple fills) (some? (seq fills)))
        can-add-fills?       (if (contains? cfg/flags :frontend-binary-fills)
                               (and (not (= :multiple fills))
                                    (< (count fills) types.fill/MAX-FILLS))
                               (not (= :multiple fills)))

        state*               (mf/use-state has-fills?)
        open?                (deref state*)

        toggle-content       (mf/use-fn #(swap! state* not))

        open-content         (mf/use-fn #(reset! state* true))

        close-content        (mf/use-fn #(reset! state* false))

        hide-fill-on-export? (:hide-fill-on-export values false)

        checkbox-ref         (mf/use-ref)

        on-add
        (mf/use-fn
         (mf/deps ids fills)
         (fn [_]
           (when can-add-fills?
             (st/emit! (dc/add-fill ids {:color default-color
                                         :opacity 1}))
             (when (or (= :multiple fills)
                       (not (some? (seq fills))))
               (open-content)))))

        on-change
        (fn [index]
          (fn [color]
            (let [color (select-keys color ctc/color-attrs)]
              (st/emit! (dc/change-fill ids color index)))))

        on-reorder
        (fn [new-index]
          (fn [index]
            (st/emit! (dc/reorder-fills ids index new-index))))

        on-remove
        (fn [index]
          (fn []
            (st/emit! (dc/remove-fill ids {:color default-color
                                           :opacity 1} index))
            (when (or (= :multiple fills)
                      (= 0 (count (seq fills))))
              (close-content))))

        on-remove-all
        (fn [_]
          (st/emit! (dc/remove-all-fills ids {:color clr/black
                                              :opacity 1})))

        on-detach
        (mf/use-fn
         (mf/deps ids)
         (fn [index]
           (fn [color]
             (let [color (dissoc color :ref-id :ref-file)]
               (st/emit! (dc/change-fill ids color index))))))

        on-change-show-fill-on-export
        (mf/use-fn
         (mf/deps ids)
         (fn [event]
           (let [value (-> event dom/get-target dom/checked?)]
             (st/emit! (dc/change-hide-fill-on-export ids (not value))))))

        disable-drag    (mf/use-state false)

        on-focus (fn [_]
                   (reset! disable-drag true))

        on-blur (fn [_]
                  (reset! disable-drag false))]

    (mf/use-layout-effect
     (mf/deps hide-fill-on-export?)
     #(let [checkbox (mf/ref-val checkbox-ref)]
        (when checkbox
           ;; Note that the "indeterminate" attribute only may be set by code, not as a static attribute.
           ;; See https://developer.mozilla.org/en-US/docs/Web/HTML/Element/input/checkbox#attr-indeterminate
          (if (= hide-fill-on-export? :multiple)
            (dom/set-attribute! checkbox "indeterminate" true)
            (dom/remove-attribute! checkbox "indeterminate")))))

    [:div {:class (stl/css :element-set)}
     [:div {:class (stl/css :element-title)}
      [:& title-bar {:collapsable  has-fills?
                     :collapsed    (not open?)
                     :on-collapsed toggle-content
                     :title        label
                     :class        (stl/css-case :title-spacing-fill (not has-fills?))}

       (when (not (= :multiple fills))
         [:> icon-button* {:variant "ghost"
                           :aria-label (tr "workspace.options.fill.add-fill")
                           :on-click on-add
                           :data-testid "add-fill"
                           :disabled (not can-add-fills?)
                           :icon "add"}])]]

     (when open?
       [:div {:class (stl/css :element-content)}
        (cond
          (= :multiple fills)
          [:div {:class (stl/css :element-set-options-group)}
           [:div {:class (stl/css :group-label)}
            (tr "settings.multiple")]
           [:> icon-button* {:variant "ghost"
                             :aria-label (tr "workspace.options.fill.remove-fill")
                             :on-click on-remove-all
                             :icon "remove"}]]

          (seq fills)
          [:& h/sortable-container {}
           (for [[index value] (d/enumerate fills)]
             [:> color-row* {:color (ctc/fill->color value)
                             :key index
                             :index index
                             :title (tr "workspace.options.fill")
                             :on-change (on-change index)
                             :on-reorder (on-reorder index)
                             :on-detach (on-detach index)
                             :on-remove (on-remove index)
                             :disable-drag disable-drag
                             :on-focus on-focus
                             :select-on-focus (not @disable-drag)
                             :on-blur on-blur}])])

        (when (or (= type :frame)
                  (and (= type :multiple) (some? (:hide-fill-on-export values))))
          [:div {:class (stl/css :checkbox)}
           [:label {:for "show-fill-on-export"
                    :class (stl/css-case :global/checked (not hide-fill-on-export?))}
            [:span {:class (stl/css-case :check-mark true
                                         :checked (not hide-fill-on-export?))}
             (when (not hide-fill-on-export?)
               i/status-tick)]
            (tr "workspace.options.show-fill-on-export")
            [:input {:type "checkbox"
                     :id "show-fill-on-export"
                     :ref checkbox-ref
                     :checked (not hide-fill-on-export?)
                     :on-change on-change-show-fill-on-export}]]])])]))
