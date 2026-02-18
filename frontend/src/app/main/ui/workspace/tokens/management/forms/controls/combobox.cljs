;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.tokens.management.forms.controls.combobox
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.types.token :as cto]
   [app.common.types.tokens-lib :as ctob]
   [app.main.data.style-dictionary :as sd]
   [app.main.ui.context :as muc]
   [app.main.ui.ds.buttons.icon-button :refer [icon-button*]]
   [app.main.ui.ds.controls.input :as ds]
   [app.main.ui.ds.controls.select :refer [get-option]]
   [app.main.ui.ds.controls.shared.options-dropdown :refer [options-dropdown*]]
   [app.main.ui.ds.controls.utilities.utils :as csu]
   [app.main.ui.ds.foundations.assets.icon :as i]
   [app.main.ui.forms :as fc]
   [app.main.ui.workspace.tokens.management.forms.controls.floating :refer [use-floating-dropdown]]
   [app.main.ui.workspace.tokens.management.forms.controls.navigation :refer [use-navigation]]
   [app.util.dom :as dom]
   [app.util.forms :as fm]
   [app.util.i18n :refer [tr]]
   [app.util.keyboard :as kbd]
   [app.util.object :as obj]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(defn- focusable-option?
  [option]
  (and (:id option)
       (not= :group (:type option))
       (not= :separator (:type option))))

(defn- first-focusable-id
  [options]
  (some #(when (focusable-option? %) (:id %)) options))

(defn next-focus-id
  [options focused-id direction]
  (let [focusable (filter focusable-option? options)
        ids (map :id focusable)
        idx (.indexOf (clj->js ids) focused-id)
        next-idx (case direction
                   :down (min (dec (count ids)) (inc (if (= idx -1) -1 idx)))
                   :up   (max 0 (dec (if (= idx -1) 0 idx))))]
    (nth ids next-idx nil)))


(defn extract-partial-token
  [value cursor]
  (let [text-before (subs value 0 cursor)
        last-open  (str/last-index-of text-before "{")
        last-close (str/last-index-of text-before "}")]
    (when (and last-open (or (nil? last-close) (> last-open last-close)))
      {:start last-open
       :partial (subs text-before (inc last-open))})))

(defn replace-active-token
  [value cursor new-name]

  (let [before     (subs value 0 cursor)
        last-open  (str/last-index-of before "{")
        last-close (str/last-index-of before "}")]

    (if (and last-open
             (or (nil? last-close)
                 (> last-open last-close)))

      (let [after-start (subs value last-open)
            close-pos   (str/index-of after-start "}")
            end         (if close-pos
                          (+ last-open close-pos 1)
                          cursor)]
        (str (subs value 0 last-open)
             "{" new-name "}"
             (subs value end)))
      (str (subs value 0 cursor)
           "{" new-name "}"
           (subs value cursor)))))

(defn active-token [value input-node]
  (let [cursor (.-selectionStart input-node)]
    (extract-partial-token value cursor)))

(defn remove-self-token [filtered-options current-token]
  (let [group (:type current-token)
        current-id (:id current-token)
        filtered-options (deref filtered-options)]
    (update filtered-options group
            (fn [options]
              (remove #(= (:id %) current-id) options)))))


(defn- select-option-by-id
  [id options-ref input-node value]
  (let [cursor     (.-selectionStart input-node)
        options    (mf/ref-val options-ref)
        options    (if (delay? options) @options options)

        option     (get-option options id)
        name       (:name option)
        final-val  (replace-active-token value cursor name)]
    final-val))

(defn- resolve-value
  [tokens prev-token token-name value]
  (let [valid-token-name?
        (and (string? token-name)
             (re-matches  cto/token-name-validation-regex token-name))

        token
        {:value value
         :name (if (or (not valid-token-name?) (str/blank? token-name))
                 "__PENPOT__TOKEN__NAME__PLACEHOLDER__"
                 token-name)}
        tokens
        (-> tokens
            ;; Remove previous token when renaming a token
            (dissoc (:name prev-token))
            (update (:name token) #(ctob/make-token (merge % prev-token token))))]

    (->> tokens
         (sd/resolve-tokens-interactive)
         (rx/mapcat
          (fn [resolved-tokens]
            (let [{:keys [errors resolved-value] :as resolved-token} (get resolved-tokens (:name token))]
              (if resolved-value
                (rx/of {:value resolved-value})
                (rx/of {:error (first errors)}))))))))

(mf/defc combobox*
  [{:keys [name tokens token token-type empty-to-end ref] :rest props}]

  (let [form              (mf/use-ctx fc/context)

        input-name        name
        token-name        (get-in @form [:data :name] nil)

        is-open*          (mf/use-state false)
        is-open           (deref is-open*)
        dropdown-pos*     (mf/use-state nil)
        dropdown-pos      (deref dropdown-pos*)
        dropdown-ready*   (mf/use-state false)
        dropdown-ready    (deref dropdown-ready*)

        listbox-id        (mf/use-id)
        filter-term*      (mf/use-state "")
        filter-term       (deref filter-term*)

        focused-id*       (mf/use-state nil)
        focused-id        (deref focused-id*)

        options-ref       (mf/use-ref nil)
        dropdown-ref      (mf/use-ref nil)
        internal-ref      (mf/use-ref nil)
        nodes-ref         (mf/use-ref nil)
        wrapper-ref       (mf/use-ref nil)
        icon-button-ref   (mf/use-ref nil)
        ref               (or ref internal-ref)

        touched?
        (and (contains? (:data @form) input-name)
             (get-in @form [:touched input-name]))

        error
        (get-in @form [:errors input-name])

        value
        (get-in @form [:data input-name] "")

        raw-tokens-by-type (mf/use-ctx muc/active-tokens-by-type)

        filtered-tokens-by-type
        (mf/with-memo [raw-tokens-by-type token-type]
          (csu/filter-tokens-for-input raw-tokens-by-type token-type))

        visible-options
        (mf/with-memo [filtered-tokens-by-type token]
          (if token
            (remove-self-token filtered-tokens-by-type token)
            filtered-tokens-by-type))

        dropdown-options
        (mf/with-memo [visible-options filter-term]
          (csu/get-token-dropdown-options visible-options (str "{" filter-term)))

        set-option-ref
        (mf/use-fn
         (fn [node]
           (let [state (mf/ref-val nodes-ref)
                 state (d/nilv state #js {})
                 id    (dom/get-data node "id")
                 state (obj/set! state id node)]
             (mf/set-ref-val! nodes-ref state))))

        toggle-dropdown
        (mf/use-fn
         (mf/deps)
         (fn [event]
           (dom/prevent-default event)
           (let [input-node (mf/ref-val ref)]
             (dom/focus! input-node))
           (swap! is-open* not)))

        resolve-stream
        (mf/with-memo [token]
          (if (contains? token :value)
            (rx/behavior-subject (:value token))
            (rx/subject)))

        on-change
        (mf/use-fn
         (mf/deps resolve-stream input-name form)
         (fn [event]
           (let [node   (dom/get-target event)
                 value  (dom/get-input-value node)
                 token  (active-token value node)]

             (fm/on-input-change form input-name value)
             (rx/push! resolve-stream value)

             (if token
               (do
                 (reset! is-open* true)
                 (reset! filter-term* (:partial token)))
               (do
                 (reset! is-open* false)
                 (reset! filter-term* ""))))))

        on-option-click
        (mf/use-fn
         (mf/deps value resolve-stream ref)
         (fn [event]
           (let [input-node (mf/ref-val ref)
                 node       (dom/get-current-target event)
                 id         (dom/get-data node "id")
                 final-val  (select-option-by-id id options-ref input-node value)]

             (fm/on-input-change form input-name final-val true)
             (rx/push! resolve-stream final-val)

             (reset! filter-term* "")
             (reset! is-open* false)

             (dom/focus! input-node)
             (let [new-cursor (+ (str/index-of final-val "}") 1)]
               (set! (.-selectionStart input-node) new-cursor)
               (set! (.-selectionEnd input-node) new-cursor)))))

        on-option-enter
        (mf/use-fn
         (mf/deps focused-id value resolve-stream)
         (fn [_]
           (let [input-node (mf/ref-val ref)
                 final-val  (select-option-by-id focused-id options-ref input-node value)]
             (fm/on-input-change form input-name final-val true)
             (rx/push! resolve-stream final-val)
             (reset! filter-term* "")
             (reset! is-open* false))))

        on-key-down
        (mf/use-fn
         (mf/deps is-open focused-id)
         (fn [event]
           (let [up?            (kbd/up-arrow? event)
                 down?          (kbd/down-arrow? event)
                 enter?         (kbd/enter? event)
                 esc?           (kbd/esc? event)
                 open-dropdown  (kbd/is-key? event "{")
                 close-dropdown (kbd/is-key? event "}")
                 options        (mf/ref-val options-ref)
                 options        (if (delay? options) @options options)]

             (cond
               open-dropdown
               (reset! is-open* true)

               close-dropdown
               (reset! is-open* false)

               down?
               (do
                 (dom/prevent-default event)
                 (if is-open
                   (let [next-id (next-focus-id options focused-id :down)]
                     (reset! focused-id* next-id))
                   (when (some? @filtered-tokens-by-type)
                     (do
                       (toggle-dropdown event)
                       (reset! focused-id* (first-focusable-id options))))))

               up?
               (when is-open
                 (dom/prevent-default event)
                 (let [next-id (next-focus-id options focused-id :up)]
                   (reset! focused-id* next-id)))

               enter?
               (do
                 (dom/prevent-default event)
                 (if is-open
                   (on-option-enter event)
                   (do
                     (reset! focused-id* (first-focusable-id options))
                     (toggle-dropdown event))))

               esc?
               (do
                 (dom/prevent-default event)
                 (reset! is-open* false))))))

        hint*
        (mf/use-state {})

        hint
        (deref hint*)

        props
        (mf/spread-props props {:on-change on-change
                                :value value
                                :variant "comfortable"
                                :hint-message (:message hint)
                                :on-key-down on-key-down
                                :hint-type (:type hint)
                                :ref ref
                                :role "combobox"
                                :aria-activedescendant focused-id
                                :aria-controls listbox-id
                                :aria-expanded is-open
                                :slot-end
                                (when (some? @filtered-tokens-by-type)
                                  (mf/html
                                   [:> icon-button*
                                    {:variant "action"
                                     :icon i/arrow-down
                                     :ref icon-button-ref
                                     :tooltip-class (stl/css :button-tooltip)
                                     :class (stl/css :invisible-button)
                                     :tab-index "-1"
                                     :aria-label (tr "ds.inputs.numeric-input.open-token-list-dropdown")
                                     :on-mouse-down dom/prevent-default
                                     :on-click toggle-dropdown}]))})
        props
        (if (and error touched?)
          (mf/spread-props props {:hint-type "error"
                                  :hint-message (:message error)})
          props)]

    (mf/with-effect [resolve-stream tokens token input-name token-name]
      (let [subs (->> resolve-stream
                      (rx/debounce 300)
                      (rx/mapcat (partial resolve-value tokens token token-name))
                      (rx/map (fn [result]
                                (d/update-when result :error
                                               (fn [error]
                                                 ((:error/fn error) (:error/value error))))))
                      (rx/subs! (fn [{:keys [error value]}]
                                  (let [touched? (get-in @form [:touched input-name])]
                                    (when touched?
                                      (if error
                                        (do
                                          (swap! form assoc-in [:extra-errors input-name] {:message error})
                                          (reset! hint* {:message error :type "error"}))
                                        (let [message (tr "workspace.tokens.resolved-value" value)]
                                          (swap! form update :extra-errors dissoc input-name)
                                          (reset! hint* {:message message :type "hint"}))))))))]
        (fn []
          (rx/dispose! subs))))

    (mf/with-effect [dropdown-options]
      (mf/set-ref-val! options-ref dropdown-options))

    (mf/with-effect [is-open* ref nodes-ref]
      (when is-open
        (let [handler (fn [event]
                        (let [input-node    (mf/ref-val ref)
                              dropdown-node (mf/ref-val dropdown-ref)
                              target        (dom/get-target event)]
                          (when (and input-node dropdown-node
                                     (not (dom/child? target input-node))
                                     (not (dom/child? target dropdown-node)))
                            (reset! is-open* false))))]

          (.addEventListener js/document "mousedown" handler)

          (fn []
            (.removeEventListener js/document "mousedown" handler)))))


    (mf/with-effect [is-open]
      (when is-open
        (let [options (mf/ref-val options-ref)
              options (if (delay? options) @options options)

              first-id (first-focusable-id options)]

          (when first-id
            (reset! focused-id* first-id)))))

    (mf/with-effect [focused-id nodes-ref]
      (when focused-id
        (let [nodes (mf/ref-val nodes-ref)
              node  (obj/get nodes focused-id)]
          (when node
            (dom/scroll-into-view-if-needed! node {:block "nearest"
                                                   :inline "nearest"})))))

    [:div {:ref wrapper-ref}
     [:> ds/input* props]
     (when ^boolean is-open
       (let [options (if (delay? dropdown-options) @dropdown-options dropdown-options)]
         (mf/portal
          (mf/html
           [:> options-dropdown* {:on-click on-option-click
                                  :class (stl/css :dropdown)
                                  :style {:visibility (if (:ready? floating) "visible" "hidden")
                                          :left (get-in floating [:style :left])
                                          :top (get-in floating [:style :top])
                                          :width (get-in floating [:style :width])}
                                  :id listbox-id
                                  :options options
                                  :focused focused-id
                                  :selected nil
                                  :align :right
                                  :empty-to-end empty-to-end
                                  :wrapper-ref dropdown-ref
                                  :ref set-option-ref}])
          (dom/get-body))))]))