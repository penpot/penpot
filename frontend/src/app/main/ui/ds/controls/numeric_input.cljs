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
   [app.main.ui.ds.controls.utilities.input-field :refer [input-field*]]
   [app.main.ui.formats :as fmt]
   [app.util.dom :as dom]
   [app.util.keyboard :as kbd]
   [app.util.simple-math :as smt]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(def ^:private schema:numeric-input
  [:map])

(mf/defc numeric-input*
  {::mf/forward-ref true
   ::mf/schema schema:numeric-input}
  [{:keys [id class min-value max-value  max-length value default on-change on-blur on-focus select-on-focus?] :rest props} ref]
  (let [default   (d/nilv default 0)
        on-change (d/nilv on-change #(prn "on-change value" %))
        select-on-focus? (d/nilv select-on-focus? true)

        id          (or id (mf/use-id))

        max-length  (d/nilv max-length max-input-length)
        ref         (or ref (mf/use-ref))

        dirty-ref   (mf/use-ref false)

        value       (when (not= :multiple value) (d/parse-double value default))

        raw-value*  (mf/use-var (fmt/format-number (d/parse-double value default)))

        last-value* (mf/use-var (d/parse-double value default))

        parse-value
        (mf/use-fn
         (mf/deps min-value max-value value default)
         (fn [raw-value]
           (let [new-value (-> raw-value
                               (str/strip-suffix ".")
                               (smt/expr-eval @last-value*))]
             (cond
               (d/num? new-value)
               (-> new-value
                   (d/max (/ sm/min-safe-int 2))
                   (d/min (/ sm/max-safe-int 2))
                   (cond-> (d/num? min-value)
                     (d/max min-value))
                   (cond-> (d/num? max-value)
                     (d/min max-value)))

               :else nil))))

        store-raw-value
        (mf/use-fn
         (mf/deps parse-value)
         (fn [event]
           (let [text (dom/get-target-val event)]
             (reset! raw-value* text))))

        update-input
        (mf/use-fn
         (fn [new-value]
           (when-let [node (mf/ref-val ref)]
             (dom/set-value! node new-value))))

        apply-value
        (mf/use-fn
         (mf/deps on-change update-input value)
         (fn [raw-value]
           (if-let [parsed (parse-value raw-value)]
             (do
               (reset! last-value* parsed)
               (when (fn? on-change)
                 (on-change parsed))
               (reset! raw-value* (fmt/format-number parsed))
               (update-input (fmt/format-number parsed)))

             ;; Cuando falla el parseo, usaremos el valor anterior o el valor por defecto
             (let [fallback-value (or @last-value* default)]
               (reset! raw-value* (fmt/format-number fallback-value))
               (update-input (fmt/format-number fallback-value))
               (when (and (fn? on-change) (not= fallback-value value))
                 (on-change fallback-value))))))

        on-blur
        (mf/use-fn
         (mf/deps parse-value)
         (fn [e]
           (when (mf/ref-val dirty-ref)
             (apply-value @raw-value*)
             (when (fn? on-blur)
               (on-blur e)))))

        handle-key-down
        (mf/use-fn
         (mf/deps apply-value update-input parse-value)
         (fn [event]
           (mf/set-ref-val! dirty-ref true)
           (let [up?    (kbd/up-arrow? event)
                 down?  (kbd/down-arrow? event)
                 enter? (kbd/enter? event)
                 esc?   (kbd/esc? event)
                 node   (mf/ref-val ref)]

             (when enter?
               (apply-value @raw-value*)
               (dom/blur! node))

             (when esc?
               (update-input (fmt/format-number @last-value*))
               (dom/blur! node)))))

        handle-focus
        (mf/use-callback
         (mf/deps on-focus select-on-focus?)
         (fn [event]
           (let [target (dom/get-target event)]
             (when (fn? on-focus)
               (mf/set-ref-val! dirty-ref true)
               (on-focus event))

             (when select-on-focus?
               (dom/select-text! target)
               ;; In webkit browsers the mouseup event will be called after the on-focus causing and unselect
               (.addEventListener target "mouseup" dom/prevent-default #js {:once true})))))


        props (mf/spread-props props {:ref ref
                                      :type "text"
                                      :id id
                                      :default-value (fmt/format-number value)
                                      :on-blur on-blur
                                      :on-key-down handle-key-down
                                      :on-focus handle-focus
                                      :on-change store-raw-value
                                      :max-length max-length})]

    [:div {:class (dm/str class " " (stl/css :input-wrapper))}
     [:> input-field* props]]))