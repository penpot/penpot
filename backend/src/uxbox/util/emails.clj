;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) 2019 Andrey Antukh <niwi@niwi.nz>

(ns uxbox.util.emails
  (:require
   [clojure.java.io :as io]
   [clojure.spec.alpha :as s]
   [cuerdas.core :as str]
   [uxbox.common.spec :as us]
   [uxbox.common.exceptions :as ex]
   [uxbox.util.template :as tmpl]))

;; --- Impl.

(def ^:private email-path "emails/%(id)s/%(lang)s.%(type)s")

(defn- build-base-email
  [data context]
  (when-not (s/valid? ::parsed-email data)
    (ex/raise :type :internal
              :code :template-parse-error
              :hint "Seems like the email template has invalid data."
              :contex data))
  {:subject (:subject data)
   :content (cond-> []
              (:body-text data) (conj {:type "text/plain"
                                       :value (:body-text data)})
              (:body-html data) (conj {:type "text/html"
                                       :value (:body-html data)}))})

(defn- render-email-part
  [type id context]
  (let [lang (:lang context :en)
        path (str/format email-path {:id (name id)
                                     :lang (name lang)
                                     :type (name type)})]
    (some-> (io/resource path)
            (tmpl/render context))))

(defn- impl-build-email
  [id context]
  (let [lang (:lang context :en)
        subj (render-email-part :subj id context)
        html (render-email-part :html id context)
        text (render-email-part :txt id context)]

    {:subject subj
     :content (cond-> []
                text (conj {:type "text/plain"
                             :value text})
                html (conj {:type "text/html"
                            :value html}))}))

;; --- Public API

(s/def ::priority #{:high :low})
(s/def ::to ::us/email)
(s/def ::from ::us/email)
(s/def ::reply-to ::us/email)
(s/def ::lang string?)

(s/def ::context
  (s/keys :req-un [::to]
          :opt-un [::reply-to ::from ::lang ::priority]))

(defn build
  ([id] (build id {}))
  ([id extra-context]
   (s/assert keyword? id)
   (fn [context]
     (us/verify ::context context)
     (when-let [spec (s/get-spec id)]
       (s/assert spec context))

     (let [context (merge (if (fn? extra-context)
                            (extra-context)
                            extra-context)
                          context)
           email (impl-build-email id context)]
       (when-not email
         (ex/raise :type :internal
                   :code :email-template-does-not-exists
                   :hint "seems like the template is wrong or does not exists."
                   ::id id))
       (cond-> (assoc email :id (name id))
         (:to context) (assoc :to [(:to context)])
         (:from context) (assoc :from (:from context))
         (:reply-to context) (assoc :reply-to (:reply-to context)))))))
