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
   [app.util.i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [app.util.simple-math :as smt]
   [cuerdas.core :as str]
   [goog.events :as events]
   [rumext.v2 :as mf]))

(defn- clamp [val min-val max-val]
  (-> val
      (max min-val)
      (min max-val)))

(defn- increment [val step min-val max-val]
  (clamp (+ val step) min-val max-val))

(defn- decrement [val step min-val max-val]
  (clamp (- val step) min-val max-val))

(defn- parse-value
  [raw-value last-value min-value max-value nillable]
  (let [new-value (-> raw-value
                      (str/strip-suffix ".")
                      (smt/expr-eval last-value))]
    (cond
      (and nillable (nil? raw-value))
      nil

      (d/num? new-value)
      (-> new-value
          (d/max (/ sm/min-safe-int 2))
          (d/min (/ sm/max-safe-int 2))
          (cond-> (d/num? min-value)
            (d/max min-value))
          (cond-> (d/num? max-value)
            (d/min max-value)))

      :else nil)))

(def ^:private schema:numeric-input
  [:map])

(mf/defc numeric-input*
  {::mf/forward-ref true
   ::mf/schema schema:numeric-input}
  [{:keys [id class value default placeholder
           min max max-length step
           is-selected-on-focus nillable
           on-change on-blur on-focus] :rest props} ref]
  (let [;; Borrar
        on-change (d/nilv on-change #(prn "on-change value" %))

        nillable     (d/nilv nillable false)
        select-on-focus (d/nilv is-selected-on-focus true)
        default      (d/parse-double default (when-not nillable 0))
        step         (d/parse-double step 1)
        min          (d/parse-double min sm/min-safe-int)
        max          (d/parse-double max sm/max-safe-int)
        max-length   (d/nilv max-length max-input-length)
        is-multiple? (= :multiple value)

        id          (or id (mf/use-id))

        ref         (or ref (mf/use-ref))
        dirty-ref   (mf/use-ref false)

        value       (cond
                      is-multiple? nil
                      (and nillable (nil? value)) nil
                      :else (d/parse-double value default))

        raw-value*  (mf/use-var
                     (cond
                       is-multiple?
                       ""

                       (and nillable (nil? value))
                       ""

                       :else
                       (fmt/format-number (d/parse-double value default))))

        last-value* (mf/use-var (d/parse-double value default))

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
         (mf/deps on-change update-input value nillable)
         (fn [raw-value]
           (if-let [parsed (parse-value raw-value @last-value* min max nillable)]
             (do
               (reset! last-value* parsed)
               (when (fn? on-change)
                 (on-change parsed))
               (reset! raw-value* (fmt/format-number parsed))
               (update-input (fmt/format-number parsed)))

             ;; Cuando falla el parseo, usaremos el valor anterior o el valor por defecto

             (if (and nillable (empty? raw-value))
               (do
                 (reset! last-value* nil)
                 (reset! raw-value* "")
                 (update-input "")
                 (when (fn? on-change)
                   (on-change nil)))

               ;; Si no es nillable, usamos el valor por defecto 
               (let [fallback-value (or @last-value* default)]
                 (reset! raw-value* (fmt/format-number fallback-value))
                 (update-input (fmt/format-number fallback-value))
                 (when (and (fn? on-change) (not= fallback-value value))
                   (on-change fallback-value)))))))

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
                 node   (mf/ref-val ref)
                 parsed (parse-value @raw-value* @last-value* min max nillable)
                 current-value (or parsed default)]

             (cond
               enter?
               (do
                 (apply-value @raw-value*)
                 (dom/blur! node))

               esc?
               (do
                 (update-input (fmt/format-number @last-value*))
                 (dom/blur! node))

               up?
               (let [new-val (increment current-value step min max)]
                 (update-input (fmt/format-number new-val))
                 (apply-value (dm/str new-val))
                 (dom/prevent-default event))

               down?
               (let [new-val (decrement current-value step min max)]
                 (update-input (fmt/format-number new-val))
                 (apply-value (dm/str new-val))
                 (dom/prevent-default event))))))

        handle-focus
        (mf/use-callback
         (mf/deps on-focus select-on-focus)
         (fn [event]
           (let [target (dom/get-target event)]
             (when (fn? on-focus)
               (mf/set-ref-val! dirty-ref true)
               (on-focus event))

             (when select-on-focus
               (dom/select-text! target)
               ;; In webkit browsers the mouseup event will be called after the on-focus causing and unselect
               (.addEventListener target "mouseup" dom/prevent-default #js {:once true})))))

        handle-mouse-wheel
        (mf/use-fn
         (fn [event]
           (when-let [node (mf/ref-val ref)]
             (when (dom/active? node)
               (let [inc? (->> (dom/get-delta-position event)
                               :y
                               (neg?))
                     parsed (parse-value @raw-value* @last-value* min max nillable)
                     current-value (or parsed default)
                     new-val (if inc?
                               (increment current-value step min max)
                               (decrement current-value step min max))]
                 (dom/prevent-default event)
                 (dom/stop-propagation event)
                 (update-input (fmt/format-number new-val))
                 (apply-value (dm/str new-val)))))))

        props (mf/spread-props props {:ref ref
                                      :type "text"
                                      :id id
                                      :placeholder (if is-multiple?
                                                     (tr "settings.multiple")
                                                     placeholder)
                                      :default-value (fmt/format-number value)
                                      :on-blur on-blur
                                      :on-key-down handle-key-down
                                      :on-focus handle-focus
                                      :on-change store-raw-value
                                      :max-length max-length})]
    (mf/with-layout-effect [handle-mouse-wheel]
      (when-let [node (mf/ref-val ref)]
        (let [key (events/listen node "wheel" handle-mouse-wheel #js {:passive false})]
          #(events/unlistenByKey key))))

    [:div {:class (dm/str class " " (stl/css :input-wrapper))}
     [:> input-field* props]]))