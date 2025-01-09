(ns app.main.ui.workspace.sidebar.options.menus.border-radius
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.types.shape.radius :as ctsr]
   [app.main.data.workspace.shapes :as dwsh]
   [app.main.store :as st]
   [app.main.ui.components.numeric-input :refer [numeric-input*]]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.foundations.assets.icon :refer [icon*]]
   [app.main.ui.hooks :as hooks]
   [app.util.i18n :as i18n :refer [tr]]
   [beicon.v2.core :as rx]
   [potok.v2.core :as ptk]
   [rumext.v2 :as mf]))

(defn all-equal?
  [shape]
  (= (:r1 shape) (:r2 shape) (:r3 shape) (:r4 shape)))

(mf/defc border-radius-menu
  {::mf/wrap-props false
   ::mf/wrap [mf/memo]}
  [{:keys [ids ids-with-children values]}]
  (let [all-equal?       (all-equal? values)
        radius-expanded* (mf/use-state false)
        radius-expanded  (deref radius-expanded*)

        change-radius
        (mf/use-fn
         (mf/deps ids-with-children)
         (fn [update-fn]
           (dwsh/update-shapes ids-with-children
                               (fn [shape]
                                 (if (ctsr/has-radius? shape)
                                   (update-fn shape)
                                   shape))
                               {:reg-objects? true
                                :attrs [:r1 :r2 :r3 :r4]})))

        toggle-radius-mode
        (mf/use-fn
         (mf/deps radius-expanded)
         (fn []
           (swap! radius-expanded* not)))

        on-single-radius-change
        (mf/use-fn
         (mf/deps ids change-radius)
         (fn [value]
           (let []
             (st/emit!
              (change-radius (fn [shape]
                               (ctsr/set-radius-to-all-corners shape value)))))))

        on-radius-4-change
        (mf/use-fn
         (mf/deps ids change-radius)
         (fn [value attr]
           (st/emit! (change-radius #(ctsr/set-radius-to-single-corner % attr value)))))

        on-radius-r1-change #(on-radius-4-change % :r1)
        on-radius-r2-change #(on-radius-4-change % :r2)
        on-radius-r3-change #(on-radius-4-change % :r3)
        on-radius-r4-change #(on-radius-4-change % :r4)

        expand-stream
        (mf/with-memo []
          (->> st/stream
               (rx/filter (ptk/type? :expand-border-radius))))]

    (hooks/use-stream
     expand-stream
     #(reset! radius-expanded* true))

    (mf/with-effect [ids]
      (reset! radius-expanded* false))

    [:div {:class (stl/css :radius)}
     (if (not radius-expanded)
       [:div {:class (stl/css :radius-1)
              :title (tr "workspace.options.radius")}
        [:> icon* {:icon-id "corner-radius"
                   :size "s"
                   :class (stl/css :icon)}]
        [:> numeric-input*
         {:placeholder (cond
                         (not all-equal?)
                         "Mixed"
                         (= :multiple (:r1 values))
                         (tr "settings.multiple")
                         :else
                         "--")
          :min 0
          :nillable true
          :on-change on-single-radius-change
          :value (if all-equal? (:r1 values) nil)}]]

       [:div {:class (stl/css :radius-4)}
        [:div {:class (stl/css :small-input)}
         [:> numeric-input*
          {:placeholder "--"
           :title (tr "workspace.options.radius-top-left")
           :min 0
           :on-change on-radius-r1-change
           :value (:r1 values)}]]

        [:div {:class (stl/css :small-input)}
         [:> numeric-input*
          {:placeholder "--"
           :title (tr "workspace.options.radius-top-right")
           :min 0
           :on-change on-radius-r2-change
           :value (:r2 values)}]]

        [:div {:class (stl/css :small-input)}
         [:> numeric-input*
          {:placeholder "--"
           :title (tr "workspace.options.radius-bottom-left")
           :min 0
           :on-change on-radius-r4-change
           :value (:r4 values)}]]

        [:div {:class (stl/css :small-input)}
         [:> numeric-input*
          {:placeholder "--"
           :title (tr "workspace.options.radius-bottom-right")
           :min 0
           :on-change on-radius-r3-change
           :value (:r3 values)}]]])

     [:> icon-button* {:class (stl/css-case :selected radius-expanded)
                       :variant "ghost"
                       :on-click toggle-radius-mode
                       :aria-label (if radius-expanded
                                     (tr "workspace.options.radius.hide-all-corners")
                                     (tr "workspace.options.radius.show-single-corners"))
                       :icon "corner-radius"}]]))
