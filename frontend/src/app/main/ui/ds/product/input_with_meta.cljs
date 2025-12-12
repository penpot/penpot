;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.ds.product.input-with-meta
  (:require-macros
   [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.main.constants :refer [max-input-length]]
   [app.main.ui.ds.controls.input :refer [input*]]
   [app.util.dom :as dom]
   [app.util.keyboard :as kbd]
   [rumext.v2 :as mf]))

(def ^:private schema:input-with-meta
  [:map
   [:value :string]
   [:meta {:optional true} :string]
   [:max-length {:optional true} :int]
   [:on-blur {:optional true} fn?]])

(mf/defc input-with-meta*
  {::mf/schema schema:input-with-meta}
  [{:keys [value meta max-length is-editing on-blur] :rest props}]
  (let [title       (if meta (str value ": " meta) value)
        editing*    (mf/use-state (d/nilv is-editing false))
        editing?    (deref editing*)
        input-ref   (mf/use-ref)
        last-node*  (mf/use-ref nil)

        ref-cb      (mf/use-fn
                     (fn [node]
                       ;; We need to keep the last not-null node for cleanup
                       (mf/set-ref-val! input-ref node)
                       (when node
                         (mf/set-ref-val! last-node* node))))

        on-edit
        (mf/use-fn
         (fn [event]
           (dom/stop-propagation event)
           (reset! editing* true)
           (dom/focus! (mf/ref-val input-ref))))

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

        props (mf/spread-props props {:ref ref-cb
                                      :default-value value
                                      :max-length (d/nilv max-length max-input-length)
                                      :auto-focus true
                                      :on-focus on-focus
                                      :on-blur on-stop-edit
                                      :on-key-down handle-key-down})]

    ;; Cleanup: Simulate a blur event
    (mf/with-effect [on-blur last-node*]
      (fn []
        (let [input (mf/ref-val last-node*)
              fake-blur-event #js {:type              "blur"
                                   :target            input
                                   :currentTarget     input
                                   :stopPropagation   (fn [])
                                   :preventDefault    (fn [])}]
          (when input
            (on-blur fake-blur-event)))))

    (if editing?
      [:div {:class (stl/css :input-with-meta-edit-container)}
       [:> input* props]]
      [:div {:class (stl/css :input-with-meta-container)
             :title title
             :on-click on-edit}
       [:span {:class (stl/css :input-with-meta-value)} value]
       (when meta
         [:span {:class (stl/css :input-with-meta-data)} meta])])))
