;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.ds.controls.numeric-input
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.data.macros :as dm]
   [app.common.schema :as sm]
   [app.main.constants :refer [max-input-length]]
   [app.main.ui.ds.controls.shared.options-dropdown :refer [options-dropdown*]]
   [app.main.ui.ds.controls.utilities.input-field :refer [input-field*]]
   [app.util.array :as array]
   [app.util.dom :as dom]
   [app.util.keyboard :as kbd]
   [app.util.object :as obj]
   [app.util.simple-math :as smt]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(def ^:private schema:numeric-input
  [:map])


(def listbox-id-index (atom 0))

(mf/defc numeric-input*
  {::mf/forward-ref true
   ::mf/schema schema:numeric-input}
  [{:keys [options id class min-value max-value max-length value nillable? default default-selected empty-to-end on-change] :rest props} ref]
  (let [id (or id (mf/use-id))

        max-length (d/nilv max-length max-input-length)
        ref (or ref (mf/use-ref))

        last-value*  (mf/use-var value)

        parse-value
        (mf/use-fn
         (mf/deps min-value max-value value nillable? default)
         (fn []
           (when-let [node (mf/ref-val ref)]
             (let [new-value (-> (dom/get-value node)
                                 (str/strip-suffix ".")
                                 (smt/expr-eval value))]
               (cond
                 (d/num? new-value)
                 (-> new-value
                     (d/max (/ sm/min-safe-int 2))
                     (d/min (/ sm/max-safe-int 2))
                     (cond-> (d/num? min-value)
                       (d/max min-value))
                     (cond-> (d/num? max-value)
                       (d/min max-value)))

                 nillable?
                 default

                 :else value)))))
        handle-change
        (mf/use-fn
         (mf/deps parse-value)
         (fn []
                         ;; Store the last value inputed
           (reset! last-value* (parse-value))))


      ;;  is-open*        (mf/use-state false)
      ;;  is-open         (deref is-open*)


      ;;  filter-value*   (mf/use-state "")
      ;;  filter-value    (deref filter-value*)

      ;;  selected-value* (mf/use-state default-selected)
      ;;  selected-value  (deref selected-value*)

      ;;  focused-value*  (mf/use-state nil)
      ;;  focused-value   (deref focused-value*)

      ;;  combobox-ref        (mf/use-ref nil)
      ;;  input-ref           (mf/use-ref nil)
      ;;  options-nodes-refs  (mf/use-ref nil)
      ;;  options-ref         (mf/use-ref nil)

      ;;  listbox-id-ref      (mf/use-ref (dm/str "listbox-" (swap! listbox-id-index inc)))
      ;;  listbox-id          (mf/ref-val listbox-id-ref)


      ;;  dropdown-options
      ;;  (mf/use-memo
      ;;   (mf/deps options filter-value)
      ;;   (fn []
      ;;     (->> options
      ;;          (array/filter (fn [option]
      ;;                          (let [lower-option (.toLowerCase (obj/get option "id"))
      ;;                                lower-filter (.toLowerCase filter-value)]
      ;;                            (.includes lower-option lower-filter)))))))


      ;;  on-option-click
      ;;  (mf/use-fn
      ;;   (mf/deps on-change)
      ;;   (fn [event]
      ;;     (dom/stop-propagation event)
      ;;     (let [node  (dom/get-current-target event)
      ;;           id    (dom/get-data node "id")]
      ;;       (reset! is-open* false)
      ;;       (when (fn? on-change)
      ;;         (on-change id)))))


      ;;  set-option-ref
      ;;  (mf/use-fn
      ;;   (fn [node id]
      ;;     (let [refs (or (mf/ref-val options-nodes-refs) #js {})
      ;;           refs (if node
      ;;                  (obj/set! refs id node)
      ;;                  (obj/unset! refs id))]
      ;;       (mf/set-ref-val! options-nodes-refs refs))))
        props (mf/spread-props props {:ref ref
                                      :type "text"
                                      :id id
                                      :on-change handle-change
                                      :max-length max-length})]
    [:div {:class (dm/str class " " (stl/css :input-wrapper))}
     [:> input-field* props]
    ;; (when (and is-open (seq dropdown-options))
    ;;   [:> options-dropdown* {:on-click on-option-click
    ;;                          :options dropdown-options
    ;;                          :selected selected-value
    ;;                          :focused focused-value
    ;;                          :set-ref set-option-ref
    ;;                          :id listbox-id
    ;;                          :empty-to-end empty-to-end
    ;;                          :data-testid "combobox-options"}])
     ]))