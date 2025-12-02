;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.workspace.tokens.management.create.input-token
  (:require
   [app.common.data :as d]
   [app.common.types.tokens-lib :as ctob]
   [app.main.data.style-dictionary :as sd]
   [app.main.data.workspace.tokens.format :as dwtf]
   [app.main.ui.ds.controls.input :refer [input*]]
   [app.main.ui.forms :as fc]
   [app.util.dom :as dom]
   [app.util.forms :as fm]
   [app.util.i18n :refer [tr]]
   [beicon.v2.core :as rx]
   [cuerdas.core :as str]
   [rumext.v2 :as mf]))

(defn- resolve-value
  [tokens prev-token value]
  (let [token
        {:value value
         :name "__PENPOT__TOKEN__NAME__PLACEHOLDER__"}
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

(mf/defc input-token*
  [{:keys [name tokens token] :rest props}]

  (let [form       (mf/use-ctx fc/context)
        input-name name

        touched?
        (and (contains? (:data @form) input-name)
             (get-in @form [:touched input-name]))

        error
        (get-in @form [:errors input-name])

        value
        (get-in @form [:data input-name] "")

        resolve-stream
        (mf/with-memo [token]
          (if (contains? token :value)
            (rx/behavior-subject (:value token))
            (rx/subject)))

        hint*
        (mf/use-state {})

        hint
        (deref hint*)

        on-change
        (mf/use-fn
         (mf/deps resolve-stream input-name)
         (fn [event]
           (let [value (-> event dom/get-target dom/get-input-value)]
             (fm/on-input-change form input-name value true)
             (rx/push! resolve-stream value))))

        props
        (mf/spread-props  props  {:on-change on-change
                                  :default-value value
                                  :variant "comfortable"
                                  :hint-message (:message hint)
                                  :hint-type (:type hint)})
        props
        (if (and error touched?)
          (mf/spread-props props {:hint-type "error"
                                  :hint-message (:message error)})
          props)]

    (mf/with-effect [resolve-stream tokens token input-name]

      (let [subs (->> resolve-stream
                      (rx/debounce 300)
                      (rx/mapcat (partial resolve-value tokens token))
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

    [:> input* props]))

(defn- on-composite-input-token-change
  ([form field value]
   (on-composite-input-token-change form field value false))
  ([form field value trim?]
   (letfn [(clean-errors [errors]
             (-> errors
                 (dissoc field)
                 (not-empty)))]
     (swap! form (fn [state]
                   (-> state
                       (assoc-in [:data :value field] (if trim? (str/trim value) value))
                       (update :errors clean-errors)
                       (update :extra-errors clean-errors)))))))

(mf/defc input-token-composite*
  [{:keys [name tokens token] :rest props}]

  (let [form       (mf/use-ctx fc/context)
        input-name name

        error
        (get-in @form [:errors :value input-name])

        value
        (get-in @form [:data :value input-name] "")

        resolve-stream
        (mf/with-memo [token]
          (if-let [value (get-in token [:value input-name])]
            (rx/behavior-subject value)
            (rx/subject)))

        hint*
        (mf/use-state {})

        hint
        (deref hint*)

        on-change
        (mf/use-fn
         (mf/deps resolve-stream input-name)
         (fn [event]
           (let [value (-> event dom/get-target dom/get-input-value)]
             (on-composite-input-token-change form input-name value true)
             (rx/push! resolve-stream value))))

        props
        (mf/spread-props props {:on-change on-change
                                :default-value value
                                :variant "comfortable"
                                :hint-message (:message hint)
                                :hint-type (:type hint)})
        props
        (if error
          (mf/spread-props props {:hint-type "error"
                                  :hint-message (:message error)})
          props)

        props (if (and (not error) (= input-name :reference))
                (mf/spread-props props {:hint-formated true})
                props)]

    (mf/with-effect [resolve-stream tokens token input-name]
      (let [subs (->> resolve-stream
                      (rx/debounce 300)
                      (rx/mapcat (partial resolve-value tokens token))
                      (rx/map (fn [result]
                                (d/update-when result :error
                                               (fn [error]
                                                 (assoc error :message ((:error/fn error) (:error/value error)))))))

                      (rx/subs!
                       (fn [{:keys [error value]}]
                         (cond
                           (and error (str/empty? (:error/value error)))
                           (do
                             (swap! form update-in [:errors :value] dissoc input-name)
                             (swap! form update-in [:data :value] dissoc input-name)
                             (swap! form update :extra-errors dissoc :value)
                             (reset! hint* {}))

                           (some? error)
                           (let [error' (:message error)]
                             (swap! form assoc-in  [:extra-errors :value input-name] {:message error'})
                             (reset! hint* {:message error' :type "error"}))

                           :else
                           (let [message (tr "workspace.tokens.resolved-value" (dwtf/format-token-value value))
                                 input-value (get-in @form [:data :value input-name] "")]
                             (swap! form update :errors dissoc :value)
                             (swap! form update :extra-errors dissoc :value)
                             (if (= input-value (str value))
                               (reset! hint* {})
                               (reset! hint* {:message message :type "hint"})))))
                       (fn [cause]
                         (js/console.log "MUU" cause))))]
        (fn []
          (rx/dispose! subs))))

    [:> input* props]))

(defn- on-composite-indexed-input-token-change
  ([form field index value composite-type]
   (on-composite-indexed-input-token-change form field index value composite-type false))
  ([form field index value composite-type trim?]
   (letfn [(clean-errors [errors]
             (-> errors
                 (dissoc field)
                 (not-empty)))]
     (swap! form (fn [state]
                   (-> state
                       (assoc-in [:data :value composite-type index field] (if trim? (str/trim value) value))
                       (update :errors clean-errors)
                       (update :extra-errors clean-errors)))))))

(mf/defc input-token-indexed*
  [{:keys [name tokens token index composite-type] :rest props}]

  (let [form       (mf/use-ctx fc/context)
        input-name name

        error
        (get-in @form [:errors :value composite-type index input-name])

        value-from-form
        (get-in @form [:data :value composite-type index input-name] "")

        resolve-stream
        (mf/with-memo [token index input-name]
          (if-let [value (get-in token [:value composite-type index input-name])]
            (rx/behavior-subject value)
            (rx/subject)))

        hint*
        (mf/use-state {})

        hint
        (deref hint*)

        on-change
        (mf/use-fn
         (mf/deps resolve-stream input-name index)
         (fn [event]
           (let [value (-> event dom/get-target dom/get-input-value)]
             (on-composite-indexed-input-token-change form input-name index value composite-type true)
             (rx/push! resolve-stream value))))

        props
        (mf/spread-props props {:on-change on-change
                                :value value-from-form
                                :variant "comfortable"
                                :hint-message (:message hint)
                                :hint-type (:type hint)})
        props
        (if error
          (mf/spread-props props {:hint-type "error"
                                  :hint-message (:message error)})
          props)

        props
        (if (and (not error) (= input-name :reference))
          (mf/spread-props props {:hint-formated true})
          props)]

    (mf/with-effect [resolve-stream tokens token input-name index composite-type]
      (let [subs (->> resolve-stream
                      (rx/debounce 300)
                      (rx/mapcat (partial resolve-value tokens token))
                      (rx/map (fn [result]
                                (d/update-when result :error
                                               (fn [error]
                                                 (assoc error :message ((:error/fn error) (:error/value error)))))))

                      (rx/subs!
                       (fn [{:keys [error value]}]
                         (cond
                           (and error (str/empty? (:error/value error)))
                           (do
                             (swap! form update-in [:errors :value composite-type index] dissoc input-name)
                             (swap! form update-in [:data :value composite-type index] dissoc input-name)
                             (swap! form update :extra-errors dissoc :value)
                             (reset! hint* {}))

                           (some? error)
                           (let [error' (:message error)]
                             (swap! form assoc-in  [:extra-errors :value composite-type index input-name] {:message error'})
                             (reset! hint* {:message error' :type "error"}))

                           :else
                           (let [message (tr "workspace.tokens.resolved-value" (dwtf/format-token-value value))
                                 input-value (get-in @form [:data :value composite-type index input-name] "")]
                             (swap! form update :errors dissoc :value)
                             (swap! form update :extra-errors dissoc :value)
                             (if (= input-value (str value))
                               (reset! hint* {})
                               (reset! hint* {:message message :type "hint"})))))))]
        (fn []
          (rx/dispose! subs))))

    [:> input* props]))
