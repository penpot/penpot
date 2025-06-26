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
   [app.main.ui.ds.tooltip :refer [tooltip*]]
   [app.main.ui.formats :as fmt]
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
   [:default-selected {:optional true} :string] ;; podria ser un id de token??
   [:empty-to-end {:optional true} :boolean]
   [:on-change {:optional true} fn?]
   [:on-blur {:optional true} fn?]
   [:on-focus {:optional true} fn?]])

(mf/defc numeric-input*
  {::mf/forward-ref true
   ::mf/schema schema:numeric-input}
  [{:keys [id class value default placeholder icon disabled
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
        ;; Raw value is used to store the raw input value
        raw-value*   (mf/use-var
                      (cond
                        is-multiple?
                        ""

                        (and nillable (nil? value))
                        ""

                        :else
                        (fmt/format-number (d/parse-double value default))))

        ;; Last value is used to store the last valid value
        last-value*  (mf/use-var (d/parse-double value default))

        ;; Refs
        wrapper-ref        (mf/use-ref nil)
        listbox-id-ref     (mf/use-ref (dm/str "tokens-listbox-" (swap! listbox-id-index inc)))
        options-nodes-refs (mf/use-ref nil)
        options-ref        (mf/use-ref nil)
        token-wrapper-ref  (mf/use-ref nil)
        ref                (or ref (mf/use-ref))
        dirty-ref          (mf/use-ref false)
        open-dropdown-ref  (mf/use-ref nil)
        listbox-id         (mf/ref-val listbox-id-ref)
        token-detach-button-ref (mf/use-ref nil)

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

             (when-not (= parsed @last-value*)
               (do
                 (reset! last-value* parsed)
                ;;  Ojo cuidado a ver si esto es asi
                 (reset! is-token* false)
                 (when (fn? on-change)
                   (on-change parsed))

                 (reset! raw-value* (fmt/format-number parsed))
                 (update-input (fmt/format-number parsed))))

             (if (and nillable (empty? raw-value))
               (do
                 (reset! last-value* nil)
                 (reset! raw-value* "")
                 (reset! is-token* false)
                 (update-input "")
                 (when (fn? on-change)
                   (on-change nil)))

               (let [fallback-value (or @last-value* default)]
                 (reset! raw-value* (fmt/format-number fallback-value))
                 (reset! last-value* (fmt/format-number fallback-value))
                 (reset! is-token* false)
                 (update-input (fmt/format-number fallback-value))
                 (when (and (fn? on-change) (not= fallback-value value))
                   (on-change fallback-value)))))))

        store-raw-value
        (mf/use-fn
         (fn [event]
           (let [text (dom/get-target-val event)]
             (reset! raw-value* text))))

        on-token-apply
        (fn [id value]
          (reset! selected-value* id)
          (reset! focused-id* nil)
          (reset! is-open* false)
          (apply-value value)
          (reset! is-token* id))

        on-option-click
        (mf/use-fn
         (mf/deps options)
         (fn [event]
           (let [node   (dom/get-current-target event)
                 id     (dom/get-data node "id")
                 option (get-option options id)
                 value  (get option :resolved)]

             (on-token-apply id value))))

        on-option-enter
        (mf/use-fn
         (mf/deps options focused-id apply-value)
         (fn [_]
           (let [option (get-option options focused-id)
                 value  (get option :resolved)]

            (on-token-apply id value))))

        on-blur
        (mf/use-fn
         (mf/deps parse-value)
         (fn [event]
           (let [target   (dom/get-related-target event)
                 self-node (mf/ref-val wrapper-ref)]
             (when-not (dom/is-child? self-node target)
               (reset! focused-id* nil)
               (reset! is-open* false)))
           (when (mf/ref-val dirty-ref)
             (apply-value @raw-value*)
             (when (fn? on-blur)
               (on-blur event)))))

        handle-key-down
        (mf/use-fn
         (mf/deps apply-value update-input parse-value options)
         (fn [event]
           (mf/set-ref-val! dirty-ref true)
           (let [up?     (kbd/up-arrow? event)
                 down?   (kbd/down-arrow? event)
                 enter?  (kbd/enter? event)
                 esc?    (kbd/esc? event)
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
                 (on-blur event))

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
                   (dom/prevent-default event)))))))

        handle-focus
        (mf/use-fn
         (mf/deps on-focus select-on-focus)
         (fn [event]
           (when (fn? on-focus)
             (on-focus event))
           (let [target (dom/get-target event)]
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
                 (apply-value (dm/str new-val)))))))

        open-dropdown
        (mf/use-fn
         (fn [event]
           (dom/prevent-default event)
           (reset! is-open* true)
           (dom/focus! (mf/ref-val ref))))

        open-dropdown-token
        (mf/use-fn
         (fn [event]
           (dom/prevent-default event)
           (reset! is-open* true)
           (dom/focus! (mf/ref-val token-wrapper-ref))))

        detach-token
        (mf/use-fn
         (mf/deps on-change)
         (fn [event]
           (dom/prevent-default event)
           (dom/stop-propagation event)
           (reset! is-token* false)
           (reset! selected-value* nil)
           (reset! focused-id* nil)
           (dom/focus! (mf/ref-val ref))))
        
        dropdown-options
        (mf/with-memo [options filter-id]
          (->> options
               (filterv (fn [option]
                          (let [option (str/lower (get option :id))
                                filter (str/lower filter-id)]
                            (str/includes? option filter))))
               (not-empty)))

        handle-pill
        (mf/use-fn
         (mf/deps detach-token)
         (fn [event]
           (let [esc?    (kbd/esc? event)
                 delete? (kbd/delete? event)
                 backspace? (kbd/backspace? event)
                 enter? (kbd/enter? event)
                 up?     (kbd/up-arrow? event)
                 down?   (kbd/down-arrow? event)
                 options (mf/ref-val options-ref)
                 len     (count options)
                 index   (d/index-of-pred options #(= focused-id (get % :id)))
                 index   (d/nilv index -1)
                 detach-btn (mf/ref-val token-detach-button-ref)
                 target (dom/get-target event)]
             (cond
               (or delete? backspace?)
               (do
                 (dom/prevent-default event)
                 (detach-token event))

               enter?
               (if is-open
                 (on-option-enter event)
                 (when (not= target detach-btn)
                   (reset! is-open* true)))

               esc?
               (dom/blur! (mf/ref-val token-wrapper-ref))

               up?
               (when is-open
                 (let [new-index (if (= index -1)
                                   (dec len)
                                   (mod (- index 1) len))]
                   (handle-focus-change options focused-id* new-index options-nodes-refs)))

               down?
               (when is-open
                 (let [new-index (if (= index -1)
                                   0
                                   (mod (+ index 1) len))]
                   (handle-focus-change options focused-id* new-index options-nodes-refs)))))))

        focus-wrapper
        (mf/use-fn
         (mf/deps token-wrapper-ref)
         (fn [event]
           (dom/prevent-default event)
           (dom/focus! (mf/ref-val token-wrapper-ref))))

        on-wrapper-blur
        (mf/use-fn
         (mf/deps)
         (fn [e]
           (let [target (dom/get-related-target e)
                 outside? (not (.contains (mf/ref-val wrapper-ref) target))]
             (when outside?
               (reset! focused-id* nil)
               (reset! is-open* false)))))

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
                                :disabled disabled
                                :slot-start (when icon
                                              (mf/html [:> icon* {:icon-id icon
                                                                  :class (stl/css :icon)}]))

                                :slot-end (when-not disabled
                                            (cond
                                              is-token
                                              (mf/html [:> icon-button* {:variant "action"
                                                                         :class (stl/css :invisible-button)
                                                                         :icon "broken-link"
                                                                         ;; TODO: add translation
                                                                         :aria-label "Detach token"
                                                                         :on-click detach-token}])
                                              (some? options)
                                              (mf/html [:> icon-button* {:variant "action"
                                                                         :icon "component"
                                                                         :class (stl/css :invisible-button)
                                                                         ;; TODO: add translation
                                                                         :aria-label "Open dropdown"
                                                                         :ref open-dropdown-ref
                                                                         :on-click open-dropdown}])))
                                :max-length max-length})]

    (mf/with-layout-effect [handle-mouse-wheel]
      (when-let [node (mf/ref-val ref)]
        (let [key (events/listen node "wheel" handle-mouse-wheel #js {:passive false})]
          #(events/unlistenByKey key))))

    (mf/with-effect [options]
      (mf/set-ref-val! options-ref options))

    [:div {:class (dm/str class " " (stl/css :input-wrapper))
           :ref wrapper-ref}
     (if is-token
       (let [token (get-option options is-token)
             label (get token :label)
             token-value (get token :resolved)]
         [:div {:class (stl/css :button-wrapper)
                :on-click focus-wrapper
                :on-key-down handle-pill
                :ref token-wrapper-ref
                :on-blur on-wrapper-blur
                :tab-index 0}
          [:> icon* {:icon-id icon
                     :class (stl/css :icon)}]
          [:> tooltip* {:content label
                        :id (dm/str id "-pill")}
           [:button {:on-click open-dropdown-token
                     :class (stl/css :pill)
                     :aria-labelledby (dm/str id "-pill")
                     :on-key-down handle-pill}
            token-value]]

          [:> icon-button* {:variant "action"
                            :class (stl/css :invisible-button)
                            :icon "broken-link"
                            ;; TODO: add translation
                            :ref token-detach-button-ref
                            :aria-label "Detach token"
                            :on-click detach-token}]])

       [:> input-field* props])

     (when is-open
       [:> options-dropdown* {:on-click on-option-click
                              :id listbox-id
                              :options dropdown-options
                              :selected selected-value
                              :focused focused-id
                              :empty-to-end empty-to-end
                              :ref set-option-ref}])]))