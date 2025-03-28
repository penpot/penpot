;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.ds.controls.input-with-values
  (:require-macros
   [app.main.style :as stl])
  (:require
   [app.main.ui.ds.controls.input :refer [input*]]
   [app.util.dom :as dom]
   [app.util.keyboard :as kbd]
   [rumext.v2 :as mf]))

(def ^:private schema:input-with-values
  [:map
   [:name :string]
   [:values {:optional true} :string]
   [:on-blur {:optional true} fn?]])

(mf/defc input-with-values*
  {::mf/props :obj
   ::mf/schema schema:input-with-values}
  [{:keys [name values on-blur] :rest props}]
  (let [editing*  (mf/use-state false)
        editing?  (deref editing*)

        input-ref (mf/use-ref)
        input     (mf/ref-val input-ref)

        title     (if values (str name ": " values) name)

        on-edit
        (mf/use-fn
         (fn [event]
           (dom/stop-propagation event)
           (reset! editing* true)
           (dom/focus! input)))

        on-stop-edit
        (mf/use-fn
         (mf/deps on-blur)
         (fn [event]
           (dom/stop-propagation event)
           (reset! editing* false)
           (when on-blur
             (on-blur event))))

        on-focus
        (mf/use-fn
         (fn [event]
           (dom/stop-propagation event)
           (dom/select-text! (dom/get-target event))))

        handle-key-down
        (mf/use-fn
         (fn [event]
           (let [enter? (kbd/enter? event)
                 esc?   (kbd/esc? event)
                 node   (dom/get-target event)]
             (when ^boolean enter? (dom/blur! node))
             (when ^boolean esc? (dom/blur! node)))))

        props (mf/spread-props props {:ref input-ref
                                      :class (stl/css :input-with-values-editing)
                                      :default-value name
                                      :auto-focus true
                                      :on-focus on-focus
                                      :on-blur on-stop-edit
                                      :on-key-down handle-key-down})]

    (if editing?
      [:div {:class (stl/css :input-with-values-edit-container)}
       [:> input* props]]
      [:div {:class (stl/css :input-with-values-container :input-with-values-grid)
             :title title
             :on-click on-edit}
       [:span {:class (stl/css :input-with-values-name)} name]
       (when values
         [:span {:class (stl/css :input-with-values-values)} values])])))
