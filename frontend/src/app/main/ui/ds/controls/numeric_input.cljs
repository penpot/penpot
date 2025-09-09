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
   [app.common.math :as mth]
   [app.common.schema :as sm]
   [app.main.constants :refer [max-input-length]]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.controls.select :refer [get-option handle-focus-change]]
   [app.main.ui.ds.controls.shared.options-dropdown :refer [options-dropdown*]]
   [app.main.ui.ds.controls.utilities.input-field :refer [input-field*]]
   [app.main.ui.ds.controls.utilities.token-field :refer [token-field*]]
   [app.main.ui.ds.foundations.assets.icon :refer [icon* icon-list] :as i]
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

(defn- increment
  "Increments `val` by `step`, clamped to [`min-val`, `max-val`]."
  [val step min-val max-val]
  (mth/clamp (+ val step) min-val max-val))

(defn- decrement
  "Decrements `val` by `step`, clamped to [`min-val`, `max-val`]."
  [val step min-val max-val]
  (mth/clamp (- val step) min-val max-val))

(defn- parse-value
  "Parses and clamps `raw-value` as a number within bounds;
   returns nil if invalid or empty."
  [raw-value last-value min-value max-value nillable]
  (let [new-value (-> raw-value
                      (str)
                      (str/strip-suffix ".")
                      (smt/expr-eval (d/parse-double last-value)))]
    (cond
      (and nillable (nil? raw-value))
      nil

      (d/num? new-value)
      (-> new-value
          (mth/max (/ sm/min-safe-int 2))
          (mth/min (/ sm/max-safe-int 2))
          (cond-> (d/num? min-value)
            (mth/max min-value))
          (cond-> (d/num? max-value)
            (mth/min max-value)))

      :else nil)))

(defn- get-option-by-name
  [options name]
  (let [options (if (delay? options) (deref options) options)]
    (d/seek #(= name (get % :name)) options)))

(defn- get-token-op
  [tokens name]
  (let [tokens (if (delay? tokens) @tokens tokens)
        xform  (filter #(= (:name %) name))]
    (reduce-kv (fn [result _ tokens]
                 (into result xform tokens))
               []
               tokens)))

(defn- clean-token-name
  [s]
  (some-> s
          (str/replace #"^\{" "")
          (str/replace #"\}$" "")))

(defn- token->dropdown-option
  [token]
  {:id (str (get token :id))
   :type :token
   :resolved-value (get token :resolved-value)
   :name (get token :name)})

(defn- generate-dropdown-options
  [tokens no-sets]
  (if (empty? tokens)
    [{:type :empty
      :label (if no-sets
               (tr "ds.inputs.numeric-input.no-applicable-tokens")
               (tr "ds.inputs.numeric-input.no-matches"))}]
    (->> tokens
         (map (fn [[type items]]
                (cons {:group true
                       :type  :group
                       :id (dm/str "group-" (name type))
                       :name  (name type)}
                      (map token->dropdown-option items))))
         (interpose [{:separator true
                      :id "separator"
                      :type :separator}])
         (apply concat)
         (vec)
         (not-empty))))

(defn- extract-partial-brace-text
  [s]
  (when-let [start (str/last-index-of s "{")]
    (subs s (inc start))))

(defn- filter-token-groups-by-name
  [tokens filter-text]
  (let [lc-filter (str/lower filter-text)]
    (into {}
          (keep (fn [[group tokens]]
                  (let [filtered (filter #(str/includes? (str/lower (:name %)) lc-filter) tokens)]
                    (when (seq filtered)
                      [group filtered]))))
          tokens)))

(defn- focusable-option?
  [option]
  (and (:id option)
       (not= :group (:type option))
       (not= :separator (:type option))))

(defn- first-focusable-id
  [options]
  (some #(when (focusable-option? %) (:id %)) options))

(defn- next-focus-index
  [options focused-id direction]
  (let [len (count options)
        start-index (or (d/index-of-pred options #(= focused-id (:id %))) -1)
        indices (case direction
                  :down (range (inc start-index) (+ len start-index))
                  :up   (range (dec start-index) (- start-index len) -1))]
    (some (fn [i]
            (let [j (mod i len)]
              (when (focusable-option? (nth options j))
                j)))
          indices)))

(def ^:private schema:icon
  [:and :string [:fn #(contains? icon-list %)]])

(def ^:private schema:numeric-input
  [:map
   [:id {:optional true} :string]
   [:class {:optional true} :string]
   [:value {:optional true} [:maybe [:or
                                     :int
                                     :string
                                     [:= :multiple]]]]
   [:default {:optional true} [:maybe :string]]
   [:placeholder {:optional true} :string]
   [:icon {:optional true} [:maybe schema:icon]]
   [:disabled {:optional true} [:maybe :boolean]]
   [:min {:optional true} [:maybe :int]]
   [:max {:optional true} [:maybe :int]]
   [:max-length {:optional true} :int]
   [:step {:optional true} [:maybe :int]]
   [:is-selected-on-focus {:optional true} :boolean]
   [:nillable {:optional true} :boolean]
   [:applied-token {:optional true} [:maybe [:or :string [:= :multiple]]]]
   [:empty-to-end {:optional true} :boolean]
   [:on-change {:optional true} fn?]
   [:on-blur {:optional true} fn?]
   [:on-focus {:optional true} fn?]
   [:on-detach {:optional true} fn?]
   [:property {:optional true} :string]
   [:align {:optional true} [:enum :left :right]]])

(mf/defc numeric-input*
  {::mf/schema schema:numeric-input}
  [{:keys [id class value default placeholder icon disabled
           min max max-length step
           is-selected-on-focus nillable
           tokens applied-token empty-to-end
           on-change on-blur on-focus on-detach
           property align ref]
    :rest props}]

  (let [;; NOTE: we use mfu/bean here for transparently handle
        ;; options provide as clojure data structures or javascript
        ;; plain objects and lists.
        tokens          (if (object? tokens)
                          (mfu/bean tokens)
                          tokens)

        value           (if (= :multiple applied-token)
                          :multiple
                          value)
        is-multiple?    (= :multiple value)
        value           (cond
                          is-multiple? nil
                          (and nillable (nil? value)) nil
                          :else (d/parse-double value default))

        ;; Default props
        nillable        (d/nilv nillable false)
        disabled        (d/nilv disabled false)
        select-on-focus (d/nilv is-selected-on-focus true)

        default         (mf/with-memo [default nillable]
                          (d/parse-double default (when-not nillable 0)))

        step            (mf/with-memo [step]
                          (d/parse-double step 1))

        min             (mf/with-memo [min]
                          (d/parse-double min sm/min-safe-int))

        max             (mf/with-memo [max]
                          (d/parse-double max sm/max-safe-int))

        max-length      (d/nilv max-length max-input-length)
        empty-to-end    (d/nilv empty-to-end false)
        internal-id     (mf/use-id)
        id              (d/nilv id internal-id)
        listbox-id      (mf/use-id)
        align           (d/nilv align :left)

        ;; State and values
        is-open*        (mf/use-state false)
        is-open         (deref is-open*)

        token-applied*  (mf/use-state applied-token)
        token-applied   (deref token-applied*)

        focused-id*     (mf/use-state nil)
        focused-id      (deref focused-id*)

        filter-id*      (mf/use-state "")
        filter-id       (deref filter-id*)

        raw-value*      (mf/use-ref nil)
        last-value*     (mf/use-ref nil)

        ;; Refs
        wrapper-ref          (mf/use-ref nil)
        nodes-ref            (mf/use-ref nil)
        options-ref          (mf/use-ref nil)
        token-wrapper-ref    (mf/use-ref nil)
        internal-ref         (mf/use-ref nil)
        ref                  (or ref internal-ref)
        dirty-ref            (mf/use-ref false)
        open-dropdown-ref    (mf/use-ref nil)
        token-detach-btn-ref (mf/use-ref nil)

        dropdown-options
        (mf/with-memo [tokens filter-id]
          (delay
            (let [tokens  (if (delay? tokens) @tokens tokens)
                  partial (extract-partial-brace-text filter-id)
                  options (if (seq partial)
                            (filter-token-groups-by-name tokens partial)
                            tokens)
                  no-sets? (nil? tokens)]
              (generate-dropdown-options options no-sets?))))

        selected-id*
        (mf/use-state (fn []
                        (if applied-token
                          (:id (get-option-by-name dropdown-options applied-token))
                          nil)))
        selected-id
        (deref selected-id*)

        set-option-ref
        (mf/use-fn
         (fn [node]
           (let [state (mf/ref-val nodes-ref)
                 state (d/nilv state #js {})
                 id    (dom/get-data node "id")
                 state (obj/set! state id node)]
             (mf/set-ref-val! nodes-ref state)
             (fn []
               (let [state (mf/ref-val nodes-ref)
                     state (d/nilv state #js {})
                     id    (dom/get-data node "id")
                     state (obj/unset! state id)]
                 (mf/set-ref-val! nodes-ref state))))))

        ;; Callbacks
        update-input
        (mf/use-fn
         (fn [new-value]
           (when-let [node (mf/ref-val ref)]
             (dom/set-value! node new-value))))

        apply-value
        (mf/use-fn
         (mf/deps on-change update-input value nillable min max)
         (fn [raw-value]
           (if-let [parsed (parse-value raw-value (mf/ref-val last-value*) min max nillable)]
             (when-not (= parsed (mf/ref-val last-value*))
               (mf/set-ref-val! last-value* parsed)
               (reset! token-applied* nil)
               (when (fn? on-change)
                 (on-change parsed))

               (mf/set-ref-val! raw-value* (fmt/format-number parsed))
               (update-input (fmt/format-number parsed)))

             (if (and nillable (empty? raw-value))
               (do
                 (mf/set-ref-val! last-value* nil)
                 (mf/set-ref-val! raw-value* "")
                 (reset! token-applied* nil)
                 (update-input "")
                 (when (fn? on-change)
                   (on-change nil)))

               (let [fallback-value (or (mf/ref-val last-value*) default)]
                 (mf/set-ref-val! raw-value* fallback-value)
                 (mf/set-ref-val!  last-value* fallback-value)
                 (reset! token-applied* nil)
                 (update-input (fmt/format-number fallback-value))

                 (when (and (fn? on-change) (not= fallback-value (str value)))
                   (on-change fallback-value)))))))

        apply-token
        (mf/use-fn
         (mf/deps min max nillable on-change tokens)
         (fn [value name]
           (let [parsed (parse-value value (mf/ref-val last-value*) min max nillable)]
             (when-not (= parsed (mf/ref-val last-value*))
               (mf/set-ref-val! last-value* parsed)
               (when (fn? on-change)
                 (on-change (get-token-op tokens name)))))))

        store-raw-value
        (mf/use-fn
         (fn [event]
           (let [text (dom/get-target-val event)]
             (mf/set-ref-val! raw-value* text)
             (reset! filter-id* text))))

        on-token-apply
        (mf/use-fn
         (mf/deps apply-token)
         (fn [id value name]
           (reset! selected-id* id)
           (reset! focused-id* nil)
           (reset! is-open* false)
           (reset! token-applied* name)
           (apply-token value name)))

        on-option-click
        (mf/use-fn
         (mf/deps on-token-apply)
         (fn [event]
           (let [node    (dom/get-current-target event)
                 id      (dom/get-data node "id")
                 options (mf/ref-val options-ref)
                 options (if (delay? options) @options options)
                 option  (get-option options id)
                 value   (get option :resolved-value)
                 name    (get option :name)]
             (on-token-apply id value name)
             (reset! filter-id* ""))))

        on-option-enter
        (mf/use-fn
         (mf/deps focused-id on-token-apply)
         (fn [_]
           (let [options (mf/ref-val options-ref)
                 options (if (delay? options) @options options)
                 option  (get-option options focused-id)
                 value   (get option :resolved-value)
                 name    (get option :name)]
             (on-token-apply focused-id value name)
             (reset! filter-id* ""))))

        on-blur
        (mf/use-fn
         (mf/deps apply-value on-blur)
         (fn [event]
           (let [target    (dom/get-related-target event)
                 self-node (mf/ref-val wrapper-ref)]
             (when-not (dom/is-child? self-node target)
               (reset! filter-id* "")
               (reset! focused-id* nil)
               (reset! is-open* false)))
           (when (mf/ref-val dirty-ref)
             (apply-value (mf/ref-val raw-value*))
             (when (fn? on-blur)
               (on-blur event)))))

        on-key-down
        (mf/use-fn
         (mf/deps is-open apply-value update-input is-open focused-id handle-focus-change)
         (fn [event]
           (mf/set-ref-val! dirty-ref true)
           (let [up?          (kbd/up-arrow? event)
                 down?        (kbd/down-arrow? event)
                 enter?       (kbd/enter? event)
                 esc?         (kbd/esc? event)
                 node         (mf/ref-val ref)
                 open-tokens  (kbd/is-key? event "{")
                 close-tokens (kbd/is-key? event "}")
                 options      (mf/ref-val options-ref)
                 options      (if (delay? options) @options options)]

             (cond
               (and (some? options) open-tokens)
               (reset! is-open* true)

               close-tokens
               (do
                 (let [name  (clean-token-name (mf/ref-val raw-value*))
                       token (get-option-by-name options name)]
                   (if token
                     (apply-token (:resolved-value token) name)
                     (apply-value (mf/ref-val last-value*)))))

               enter?
               (if is-open
                 (do
                   (dom/prevent-default event)
                   (if focused-id
                     (on-option-enter event)
                     (let [option-id (first-focusable-id options)
                           option (get-option options option-id)
                           value  (get option :resolved-value)
                           name   (get option :name)]
                       (on-token-apply option-id value name)
                       (reset! filter-id* ""))))
                 (on-blur event))

               esc?
               (do
                 (update-input (fmt/format-number (mf/ref-val last-value*)))
                 (reset! is-open* false)
                 (dom/blur! node))

               (kbd/home? event)
               (handle-focus-change options focused-id* 0 (mf/ref-val nodes-ref))

               up?
               (if is-open
                 (let [new-index (next-focus-index options focused-id :up)]
                   (dom/prevent-default event)
                   (handle-focus-change options focused-id* new-index (mf/ref-val nodes-ref)))

                 (let [parsed  (parse-value (mf/ref-val raw-value*) (mf/ref-val last-value*) min max nillable)
                       current-value (or parsed default)
                       new-val (increment current-value step min max)]
                   (dom/prevent-default event)
                   (update-input (fmt/format-number new-val))
                   (apply-value (dm/str new-val))))

               down?
               (if is-open
                 (let [new-index (next-focus-index options focused-id :down)]
                   (dom/prevent-default event)
                   (handle-focus-change options focused-id* new-index (mf/ref-val nodes-ref)))

                 (let [parsed  (parse-value (mf/ref-val raw-value*) (mf/ref-val last-value*) min max nillable)
                       current-value (or parsed default)
                       new-val (decrement current-value step min max)]
                   (dom/prevent-default event)
                   (update-input (fmt/format-number new-val))
                   (apply-value (dm/str new-val))))))))

        on-focus
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

        on-mouse-wheel
        (mf/use-fn
         (mf/deps apply-value parse-value min max nillable ref default step min max)
         (fn [event]
           (when-let [node (mf/ref-val ref)]
             (when (dom/active? node)
               (let [inc? (->> (dom/get-delta-position event)
                               :y
                               (neg?))
                     parsed (parse-value (mf/ref-val raw-value*) (mf/ref-val last-value*) min max nillable)
                     current-value (or parsed default)
                     new-val (if inc?
                               (increment current-value step min max)
                               (decrement current-value step min max))]
                 (dom/prevent-default event)
                 (dom/stop-propagation event)
                 (apply-value (dm/str new-val)))))))

        open-dropdown
        (mf/use-fn
         (mf/deps disabled ref)
         (fn [event]
           (when-not disabled
             (dom/prevent-default event)
             (swap! is-open* not)
             (dom/focus! (mf/ref-val ref)))))

        open-dropdown-token
        (mf/use-fn
         (mf/deps disabled token-wrapper-ref)
         (fn [event]
           (when-not disabled
             (dom/prevent-default event)
             (swap! is-open* not)
             (dom/focus! (mf/ref-val token-wrapper-ref)))))

        detach-token
        (mf/use-fn
         (mf/deps on-detach tokens disabled token-applied)
         (fn [event]
           (let [token (get-token-op tokens token-applied)]
             (when-not disabled
               (dom/prevent-default event)
               (dom/stop-propagation event)
               (reset! token-applied* nil)
               (reset! selected-id* nil)
               (reset! focused-id* nil)
               (dom/focus! (mf/ref-val ref))
               (when on-detach
                 (on-detach token))))))

        on-token-key-down
        (mf/use-fn
         (mf/deps detach-token is-open)
         (fn [event]
           (let [esc?       (kbd/esc? event)
                 delete?    (kbd/delete? event)
                 backspace? (kbd/backspace? event)
                 enter?     (kbd/enter? event)
                 up?        (kbd/up-arrow? event)
                 down?      (kbd/down-arrow? event)
                 options    (mf/ref-val options-ref)
                 detach-btn (mf/ref-val token-detach-btn-ref)
                 target     (dom/get-target event)]

             (when-not disabled
               (cond
                 (or delete? backspace?)
                 (do
                   (dom/prevent-default event)
                   (detach-token event)
                   (dom/focus! (mf/ref-val ref)))

                 enter?
                 (if is-open
                   (do
                     (dom/prevent-default event)
                     (dom/stop-propagation event)
                     (on-option-enter event))
                   (when (not= target detach-btn)
                     (dom/prevent-default event)
                     (reset! is-open* true)))

                 esc?
                 (dom/blur! (mf/ref-val token-wrapper-ref))

                 up?
                 (when is-open
                   (let [new-index (next-focus-index options focused-id :up)]
                     (dom/prevent-default event)
                     (handle-focus-change options focused-id* new-index nodes-ref)))

                 down?
                 (when is-open
                   (let [new-index (next-focus-index options focused-id :down)]
                     (dom/prevent-default event)
                     (handle-focus-change options focused-id* new-index nodes-ref))))))))

        input-props
        (mf/spread-props props {:ref ref
                                :type "text"
                                :id id
                                :placeholder (if is-multiple?
                                               (tr "labels.mixed-values")
                                               placeholder)
                                :default-value (or (mf/ref-val last-value*) (fmt/format-number value))
                                :on-blur on-blur
                                :on-key-down on-key-down
                                :on-focus on-focus
                                :on-change store-raw-value
                                :disabled disabled
                                :slot-start (when icon
                                              (mf/html [:> tooltip*
                                                        {:content property
                                                         :id property}
                                                        [:> icon* {:icon-id icon
                                                                   :aria-labelledby property
                                                                   :class (stl/css :icon)}]]))
                                :slot-end (when-not disabled
                                            (when (some? tokens)
                                              (mf/html [:> icon-button* {:variant "action"
                                                                         :icon i/tokens
                                                                         :class (stl/css :invisible-button)
                                                                         :aria-label (tr "ds.inputs.numeric-input.open-token-list-dropdown")
                                                                         :ref open-dropdown-ref
                                                                         :on-click open-dropdown}])))
                                :max-length max-length})

        token-props
        (when (and token-applied (not= :multiple token-applied))
          (let [token       (get-option-by-name dropdown-options token-applied)
                id          (get token :id)
                label       (get token :name)
                token-value (or (get token :resolved-value)
                                (or (mf/ref-val last-value*)
                                    (fmt/format-number value)))]
            (mf/spread-props props
                             {:id id
                              :label label
                              :value token-value
                              :on-click open-dropdown-token
                              :on-token-key-down on-token-key-down
                              :disabled disabled
                              :on-blur on-blur
                              :slot-start (when icon
                                            (mf/html [:> tooltip*
                                                      {:content property
                                                       :id property}
                                                      [:> icon* {:icon-id icon
                                                                 :aria-labelledby property
                                                                 :class (stl/css :icon)}]]))
                              :token-wrapper-ref token-wrapper-ref
                              :token-detach-btn-ref token-detach-btn-ref
                              :detach-token detach-token})))]

    (mf/with-effect [value default applied-token]
      (let [value' (cond
                     is-multiple?
                     ""

                     (and nillable (nil? value))
                     ""

                     :else
                     (fmt/format-number (d/parse-double value default)))]

        (mf/set-ref-val! raw-value* value')
        (mf/set-ref-val! last-value* value')
        (reset! token-applied* applied-token)
        (if applied-token
          (let [token-id (:id (get-option-by-name dropdown-options applied-token))]
            (reset! selected-id* token-id))
          (reset! selected-id* nil))

        (when-let [node (mf/ref-val ref)]
          (dom/set-value! node value'))))

    (mf/with-layout-effect [on-mouse-wheel]
      (when-let [node (mf/ref-val ref)]
        (let [key (events/listen node "wheel" on-mouse-wheel #js {:passive false})]
          #(events/unlistenByKey key))))

    (mf/with-effect [dropdown-options]
      (mf/set-ref-val! options-ref dropdown-options))

    [:div {:class (dm/str class " " (stl/css :input-wrapper))
           :ref wrapper-ref}

     (if (and (some? token-applied)
              (not= :multiple token-applied))
       [:> token-field* token-props]
       [:> input-field* input-props])

     (when ^boolean is-open
       (let [options (if (delay? dropdown-options) @dropdown-options dropdown-options)]
         [:> options-dropdown* {:on-click on-option-click
                                :id listbox-id
                                :options options
                                :selected selected-id
                                :focused focused-id
                                :align align
                                :empty-to-end empty-to-end
                                :ref set-option-ref}]))]))
