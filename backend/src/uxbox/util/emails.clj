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
   [instaparse.core :as insta]
   [uxbox.common.spec :as us]
   [uxbox.common.exceptions :as ex]
   [uxbox.util.template :as tmpl]))

;; --- Impl.

(def ^:private grammar
  (str "message = part*"
       "part = begin header body end; "
       "header = tag* eol; "
       "tag = space keyword; "
       "body = line*; "
       "begin = #'--\\s+begin\\s+'; "
       "end = #'--\\s+end\\s*' eol*; "
       "keyword = #':[\\w\\-]+'; "
       "space = #'\\s*'; "
       "line = #'.*\\n'; "
       "eol = ('\\n' | '\\r\\n'); "))

(def ^:private parse-fn (insta/parser grammar))
(def ^:private email-path "emails/%(lang)s/%(id)s.mustache")

(defn- parse-template
  [content]
  (loop [state {}
         parts (drop 1 (parse-fn content))]
    (if-let [[_ _ header body] (first parts)]
      (let [type (get-in header [1 2 1])
            type (keyword (str/slice type 1))
            content (apply str (map second (rest body)))]
        (recur (assoc state type (str/trim content " \n"))
               (rest parts)))
      state)))

(s/def ::subject string?)
(s/def ::body-text string?)
(s/def ::body-html string?)

(s/def ::parsed-email
  (s/keys :req-un [::subject ::body-html ::body-html]))

(defn- build-base-email
  [data context]
  (when-not (s/valid? ::parsed-email data)
    (ex/raise :type :internal
              :code :template-parse-error
              :hint "Seems like the email template has invalid data."
              :contex data))
  {:subject (:subject data)
   :body [:alternative
          {:type "text/plain; charset=utf-8"
           :content (:body-text data)}
          {:type "text/html; charset=utf-8"
           :content (:body-html data)}]})

(defn- impl-build-email
  [id context]
  (let [lang (:lang context :en)
        path (str/format email-path {:id (name id) :lang (name lang)})]
    (-> (tmpl/render path context)
        (parse-template)
        (build-base-email context))))

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

     (let [context (merge extra-context context)
           email (impl-build-email id context)]
       (when-not email
         (ex/raise :type :internal
                   :code :email-template-does-not-exists
                   :hint "seems like the template is wrong or does not exists."
                   ::id id))
       (cond-> (assoc email :id (name id))
         (:to context) (assoc :to (:to context))
         (:from context) (assoc :from (:from context))
         (:reply-to context) (assoc :reply-to (:reply-to context)))))))
