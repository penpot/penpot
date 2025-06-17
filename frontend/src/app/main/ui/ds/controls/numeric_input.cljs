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
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.controls.select :refer [get-option handle-focus-change]]
   [app.main.ui.ds.controls.shared.options-dropdown :refer [options-dropdown*]]
   [app.main.ui.ds.controls.shared.token-option :refer [schema:token-option]]
   [app.main.ui.ds.controls.utilities.input-field :refer [input-field*]]
   [app.main.ui.ds.foundations.assets.icon :refer [icon* icon-list]]
   [app.main.ui.formats :as fmt]
   [app.util.array :as array]
   [app.util.dom :as dom]
   [app.util.i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [app.util.object :as obj]
   [app.util.simple-math :as smt]
   [cuerdas.core :as str]
   [goog.events :as events]
   [rumext.v2 :as mf]
   [rumext.v2.util :as mfu]))

;; Fire this
(def listbox-id-index (atom 0))

(defn- clamp
  "Returns `min-val` if `val` is less than `min-val`, `max-val`
   if greater than `max-val`, or `val` itself if within bounds."
  [val min-val max-val]
  (-> val
      (max min-val)
      (min max-val)))

(defn- increment
  "Increments `val` by `step`, clamped to [`min-val`, `max-val`]."
  [val step min-val max-val]
  (clamp (+ val step) min-val max-val))

(defn- decrement
  "Decrements `val` by `step`, clamped to [`min-val`, `max-val`]."
  [val step min-val max-val]
  (clamp (- val step) min-val max-val))

(defn- parse-value
  "Parses and clamps `raw-value` as a number within bounds;
   returns nil if invalid or empty."
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

;; TODO: Review schema props
(def ^:private schema:numeric-input
  [:map
   [:id {:optional true} :string]
   [:class {:optional true} :string]
   [:value {:optional true} [:maybe :string]]
   [:default {:optional true} [:maybe :string]]
   [:placeholder {:optional true} :string]
   [:icon {:optional true} [:maybe [:and :string [:fn #(contains? icon-list %)]]]]
   [:min {:optional true} [:maybe :int]]
   [:max {:optional true} [:maybe :int]]
   [:max-length {:optional true} :int]
   [:step {:optional true} [:maybe :int]]
   [:is-selected-on-focus {:optional true} :boolean]
   [:nillable {:optional true} :boolean]
   [:options {:optional true}
    [:vector {:min 1}
     schema:token-option]]
   [:default-selected {:optional true} :string]
   [:empty-to-end {:optional true} :boolean]
   [:on-change {:optional true} fn?]
   [:on-blur {:optional true} fn?]
   [:on-focus {:optional true} fn?]])

(mf/defc numeric-input*
  {::mf/forward-ref true
   ::mf/schema schema:numeric-input}
  [{:keys [id class value default placeholder icon
           min max max-length step
           is-selected-on-focus nillable
           options default-selected empty-to-end
           on-change on-blur on-focus] :rest props} ref]

  (let [;; NOTE: we use mfu/bean here for transparently handle
        ;; options provide as clojure data structures or javascript
        ;; plain objects and lists.
        options      (if (array? options)
                       (mfu/bean options)
                       options)
        
        ;; Borrar
        on-change (d/nilv on-change #(prn "on-change value" %))
        ;; Defautl props
        nillable        (d/nilv nillable false)
        select-on-focus (d/nilv is-selected-on-focus true)
        default         (d/parse-double default (when-not nillable 0))
        step            (d/parse-double step 1)
        min             (d/parse-double min sm/min-safe-int)
        max             (d/parse-double max sm/max-safe-int)
        max-length      (d/nilv max-length max-input-length)
        empty-to-end    (d/nilv empty-to-end false)
        id              (or id (mf/use-id))

        ;; State and values
        is-open*           (mf/use-state false)
        is-open            (deref is-open*)

        is-token*          (mf/use-state false)
        is-token           (deref is-token*)

        selected-value*    (mf/use-state default-selected)
        selected-value     (deref selected-value*)

        focused-id*     (mf/use-state nil)
        focused-id      (deref focused-id*)

        filter-id*   (mf/use-state "")
        filter-id    (deref filter-id*)

        is-multiple?       (= :multiple value)

        value        (cond
                       is-multiple? nil
                       (and nillable (nil? value)) nil
                       :else (d/parse-double value default))

        raw-value*   (mf/use-var
                      (cond
                        is-multiple?
                        ""

                        (and nillable (nil? value))
                        ""

                        :else
                        (fmt/format-number (d/parse-double value default))))

        last-value*  (mf/use-var (d/parse-double value default))

        ;; Refs
        wrapper-ref        (mf/use-ref nil)
        listbox-id-ref     (mf/use-ref (dm/str "tokens-listbox-" (swap! listbox-id-index inc)))
        options-nodes-refs (mf/use-ref nil)
        options-ref        (mf/use-ref nil)
        ref                (or ref (mf/use-ref))
        dirty-ref          (mf/use-ref false)
        open-dropdown-ref  (mf/use-ref nil)
        listbox-id         (mf/ref-val listbox-id-ref)

        set-option-ref
        (mf/use-fn
         (fn [node]
           (let [state (mf/ref-val options-nodes-refs)
                 state (d/nilv state #js {})
                 id    (dom/get-data node "id")
                 state (obj/set! state id node)]
             (mf/set-ref-val! options-nodes-refs state)
             (fn []
               (let [state (mf/ref-val options-nodes-refs)
                     state (d/nilv state #js {})
                     id    (dom/get-data node "id")
                     state (obj/unset! state id)]
                 (mf/set-ref-val! options-nodes-refs state))))))

        ;; Callbacks
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

        store-raw-value
        (mf/use-fn
         (mf/deps parse-value)
         (fn [event]
           (let [text (dom/get-target-val event)]
             (reset! raw-value* text)
             (reset! is-token* false))))

        on-option-click
        (mf/use-fn
         (mf/deps options apply-value)
         (fn [event]
           (let [node   (dom/get-current-target event)
                 id     (dom/get-data node "id")
                 option (get-option options id)
                 value  (get option :resolved)]

             (reset! selected-value* id)
             (reset! focused-id* nil)
             (reset! is-open* false)

             (apply-value value)
             (reset! is-token* true))))

        on-option-enter
        (mf/use-fn
         (mf/deps options focused-id apply-value)
         (fn [_]
           (let [option (get-option options focused-id)
                 value  (get option :resolved)]

             (reset! selected-value* focused-id)
             (reset! focused-id* nil)
             (reset! is-open* false)

             (apply-value value)
             (reset! is-token* true))))

        on-blur
        (mf/use-fn
         (mf/deps parse-value)
         (fn [e]
           (let [target (.-relatedTarget e)
                 outside? (not (.contains (mf/ref-val wrapper-ref) target))]
             (when outside?
               (reset! focused-id* nil)
               (reset! is-open* false)))
           (when (mf/ref-val dirty-ref)
             (apply-value @raw-value*)
             (when (fn? on-blur)
               (on-blur e)))))

        handle-key-down
        (mf/use-fn
         (mf/deps apply-value update-input parse-value options)
         (fn [event]
           (mf/set-ref-val! dirty-ref true)
           (let [up?     (kbd/up-arrow? event)
                 down?   (kbd/down-arrow? event)
                 enter?  (kbd/enter? event)
                 esc?    (kbd/esc? event)
                 tab?    (kbd/tab? event)
                 node    (mf/ref-val ref)
                 tokens? (= (.-key event) "{")
                 parsed  (parse-value @raw-value* @last-value* min max nillable)
                 current-value (or parsed default)
                 options (mf/ref-val options-ref)
                 len     (count options)
                 index   (d/index-of-pred options #(= focused-id (get % :id)))
                 index   (d/nilv index -1)]

             (cond
               (and (some? options) tokens?)
               (reset! is-open* true)

               enter?
               (if is-open
                 (on-option-enter event)
                 (do
                   (apply-value @raw-value*)
                   (dom/blur! node)))

               esc?
               (do
                 (update-input (fmt/format-number @last-value*))
                 (reset! is-open* false)
                 (dom/blur! node))

               up?
               (if is-open
                 (let [new-index (if (= index -1)
                                   (dec len)
                                   (mod (- index 1) len))]
                   (handle-focus-change options focused-id* new-index options-nodes-refs))

                 (let [new-val (increment current-value step min max)]
                   (update-input (fmt/format-number new-val))
                   (apply-value (dm/str new-val))
                   (dom/prevent-default event)))

               down?
               (if is-open
                 (let [new-index (if (= index -1)
                                   0
                                   (mod (+ index 1) len))]
                   (handle-focus-change options focused-id* new-index options-nodes-refs))

                 (let [new-val (decrement current-value step min max)]
                   (update-input (fmt/format-number new-val))
                   (apply-value (dm/str new-val))
                   (dom/prevent-default event)))

               tab?
               (if is-open
                 (do
                   (prn "index" index)
                   (prn "tengo que targetear el primer elemento de la lista"))
                 (->  (mf/ref-val open-dropdown-ref)
                      (dom/set-attribute! "tabIndex" "0")
                      (dom/focus!)))))))

        handle-focus
        (mf/use-fn
         (mf/deps on-focus select-on-focus)
         (fn [event]
           (prn "handle-focus")
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
           (prn "handle-mouse-wheel")
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

        open-dropdown
        (mf/use-fn
         (fn [event]
           (dom/prevent-default event)
           (reset! is-open* true)
           (dom/focus! (mf/ref-val ref))))

        handle-dropdown
        (mf/use-fn
         (mf/deps open-dropdown)
         (fn [event]
           (dom/prevent-default event)
           (when (kbd/enter? event)
             (dom/set-attribute! (mf/ref-val open-dropdown-ref) "tabIndex" "-1")
             (open-dropdown))
           (when (kbd/esc? event)
             (do
               (reset! is-open* false)
               (dom/blur! (mf/ref-val open-dropdown-ref))
               (dom/set-attribute! (mf/ref-val open-dropdown-ref) "tabIndex" "-1")))
           (when (kbd/tab? event)
             (dom/stop-propagation event)

             (dom/blur! (mf/ref-val open-dropdown-ref))
             (dom/set-attribute! (mf/ref-val open-dropdown-ref) "tabIndex" "-1"))))

        detach-token
        (mf/use-fn
         (mf/deps on-change)
         (fn [event]
           (prn "entro en el detach-token")
           (dom/prevent-default event)
           (reset! is-token* false)
           (reset! selected-value* nil)
           (reset! focused-id* nil)
           (dom/focus! (mf/ref-val ref))))

        handle-detach
        (mf/use-fn
         (mf/deps detach-token)
         (fn [event]
           (prn "entro en el handle-detach")
           (dom/prevent-default event)
           (when (kbd/enter? event)
             (detach-token))
           (when (kbd/tab? event)
             (dom/stop-propagation event)
             (prn "debo focusear siguiente element0"))))
        
        dropdown-options
        (mf/with-memo [options filter-id]
          (->> options
               (filterv (fn [option]
                          (let [option (str/lower (get option :id))
                                filter (str/lower filter-id)]
                            (str/includes? option filter))))
               (not-empty)))


        props
        (mf/spread-props props {:ref ref
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
                                :slot-start (when icon
                                              (mf/html [:> icon* {:icon-id icon
                                                                  :class (stl/css :icon)}]))

                                :slot-end (cond
                                            is-token
                                            (mf/html [:> icon-button* {:variant "action"
                                                                       :class (stl/css :invisible-button)
                                                                       :icon "broken-link"
                                                                       :aria-label "Detach token"
                                                                       :on-key-down handle-detach
                                                                       :on-click detach-token}])
                                            (some? options)
                                            (mf/html [:> icon-button* {:variant "action"
                                                                       :icon "component"
                                                                       :class (stl/css :invisible-button)
                                                                       :aria-label "Open dropdown"
                                                                       :tab-index -1
                                                                       :ref open-dropdown-ref
                                                                       :on-key-down handle-dropdown
                                                                       :on-click open-dropdown}]))
                                :max-length max-length})]

    (mf/with-layout-effect [handle-mouse-wheel]
      (when-let [node (mf/ref-val ref)]
        (let [key (events/listen node "wheel" handle-mouse-wheel #js {:passive false})]
          #(events/unlistenByKey key))))

    (mf/with-effect [options]
      (mf/set-ref-val! options-ref options))

    [:div {:class (dm/str class " " (stl/css :input-wrapper))
           :ref wrapper-ref}
     [:> input-field* props]

     (when is-open
       [:> options-dropdown* {:on-click on-option-click
                              :id listbox-id
                              :options dropdown-options
                              :selected selected-value
                              :focused focused-id
                              :empty-to-end empty-to-end
                              :ref set-option-ref}])]))