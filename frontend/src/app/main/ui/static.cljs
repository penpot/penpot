;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) KALEIDOS INC

(ns app.main.ui.static
  (:require-macros [app.main.style :as stl])
  (:require
   [app.common.data :as d]
   [app.common.pprint :as pp]
   [app.common.uri :as u]
   [app.main.data.events :as ev]
   [app.main.store :as st]
   [app.main.ui.icons :as i]
   [app.util.dom :as dom]
   [app.util.globals :as globals]
   [app.util.i18n :refer [tr]]
   [app.util.router :as rt]
   [app.util.webapi :as wapi]
   [potok.v2.core :as ptk]
   [rumext.v2 :as mf]))

(mf/defc error-container
  {::mf/wrap-props false}
  [{:keys [children]}]
  (let [on-click (mf/use-callback #(set! (.-href globals/location) "/"))]
    [:section {:class (stl/css :exception-layout)}
     [:button
      {:class (stl/css :exception-header)
       :on-click on-click}
      i/logo-icon]
     [:div {:class (stl/css :deco-before)} i/logo-error-screen]

     [:div {:class (stl/css :exception-content)}
      [:div {:class (stl/css :container)} children]]

     [:div {:class (stl/css :deco-after)} i/logo-error-screen]]))

(mf/defc invalid-token
  []
  [:> error-container {}
   [:div {:class (stl/css :main-message)} (tr "errors.invite-invalid")]
   [:div {:class (stl/css :desc-message)} (tr "errors.invite-invalid.info")]])

(mf/defc not-found
  []
  [:> error-container {}
   [:div {:class (stl/css :main-message)} (tr "labels.not-found.main-message")]
   [:div {:class (stl/css :desc-message)} (tr "labels.not-found.desc-message")]])

(mf/defc bad-gateway
  []
  (let [handle-retry
        (mf/use-callback
         (fn [] (st/emit! (rt/assign-exception nil))))]
    [:> error-container {}
     [:div {:class (stl/css :main-message)} (tr "labels.bad-gateway.main-message")]
     [:div {:class (stl/css :desc-message)} (tr "labels.bad-gateway.desc-message")]
     [:div {:class (stl/css :sign-info)}
      [:button {:on-click handle-retry} (tr "labels.retry")]]]))

(mf/defc service-unavailable
  []
  (let [on-click (mf/use-fn #(st/emit! (rt/assign-exception nil)))]
    [:> error-container {}
     [:div {:class (stl/css :main-message)} (tr "labels.service-unavailable.main-message")]
     [:div {:class (stl/css :desc-message)} (tr "labels.service-unavailable.desc-message")]
     [:div {:class (stl/css :sign-info)}
      [:button {:on-click on-click} (tr "labels.retry")]]]))


(defn generate-report
  [data]
  (try
    (let [team-id    (:current-team-id @st/state)
          profile-id (:profile-id @st/state)

          trace      (:app.main.errors/trace data)
          instance   (:app.main.errors/instance data)
          content    (with-out-str
                       (println "Hint:   " (or (:hint data) (ex-message instance) "--"))
                       (println "Prof ID:" (str (or profile-id "--")))
                       (println "Team ID:" (str (or team-id "--")))

                       (when-let [file-id (:file-id data)]
                         (println "File ID:" (str file-id)))

                       (println)

                       (println "Data:")
                       (loop [data data]
                         (-> (d/without-qualified data)
                             (dissoc :explain)
                             (d/update-when :data (constantly "(...)"))
                             (pp/pprint {:level 8 :length 10}))

                         (println)

                         (when-let [explain (:explain data)]
                           (print explain))

                         (when (and (= :server-error (:type data))
                                    (contains? data :data))
                           (recur (:data data))))

                       (println "Trace:")
                       (println trace)
                       (println)

                       (println "Last events:")
                       (pp/pprint @st/last-events {:length 200})

                       (println))]
      (wapi/create-blob content "text/plain"))
    (catch :default err
      (.error js/console err)
      nil)))


(mf/defc internal-error
  {::mf/props :obj}
  [{:keys [data]}]
  (let [on-click   (mf/use-fn #(st/emit! (rt/assign-exception nil)))
        report-uri (mf/use-ref nil)
        report     (mf/use-memo (mf/deps data) #(generate-report data))

        on-download
        (mf/use-fn
         (fn [event]
           (dom/prevent-default event)
           (when-let [uri (mf/ref-val report-uri)]
             (dom/trigger-download-uri "report" "text/plain" uri))))]

    (mf/with-effect [report]
      (when (some? report)
        (let [uri    (wapi/create-uri report)]
          (mf/set-ref-val! report-uri uri)
          (fn []
            (wapi/revoke-uri uri)))))

    [:> error-container {}
     [:div {:class (stl/css :main-message)} (tr "labels.internal-error.main-message")]
     [:div {:class (stl/css :desc-message)} (tr "labels.internal-error.desc-message")]
     (when (some? report)
       [:a {:on-click on-download} "Download report.txt"])
     [:div {:class (stl/css :sign-info)}
      [:button {:on-click on-click} (tr "labels.retry")]]]))

(mf/defc exception-page
  {::mf/props :obj}
  [{:keys [data route] :as props}]
  (let [type (:type data)
        path (:path route)
        query-params (u/map->query-string (:query-params route))]
    (st/emit! (ptk/event ::ev/event {::ev/name "exception-page" :type type :path path :query-params query-params}))
    (case (:type data)
      :not-found
      [:& not-found]

      :bad-gateway
      [:& bad-gateway]

      :service-unavailable
      [:& service-unavailable]

      [:> internal-error props])))
