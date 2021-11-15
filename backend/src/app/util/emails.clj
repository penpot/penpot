;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) UXBOX Labs SL

(ns app.util.emails
  (:require
   [app.common.data :as d]
   [app.common.exceptions :as ex]
   [app.common.spec :as us]
   [app.util.template :as tmpl]
   [clojure.java.io :as io]
   [clojure.spec.alpha :as s]
   [cuerdas.core :as str])
  (:import
   java.util.Properties
   jakarta.mail.Message$RecipientType
   jakarta.mail.Session
   jakarta.mail.Transport
   jakarta.mail.internet.InternetAddress
   jakarta.mail.internet.MimeBodyPart
   jakarta.mail.internet.MimeMessage
   jakarta.mail.internet.MimeMultipart))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Email Building
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- parse-address
  [v]
  (InternetAddress/parse ^String v))

(defn- ^Message$RecipientType resolve-recipient-type
  [type]
  (case type
    :to  Message$RecipientType/TO
    :cc  Message$RecipientType/CC
    :bcc Message$RecipientType/BCC))

(defn- assign-recipient
  [^MimeMessage mmsg type address]
  (if (sequential? address)
    (reduce #(assign-recipient %1 type %2) mmsg address)
    (let [address (parse-address address)
          type    (resolve-recipient-type type)]
      (.addRecipients mmsg type address)
      mmsg)))

(defn- assign-recipients
  [mmsg {:keys [to cc bcc] :as params}]
  (cond-> mmsg
    (some? to)  (assign-recipient :to to)
    (some? cc)  (assign-recipient :cc cc)
    (some? bcc) (assign-recipient :bcc bcc)))

(defn- assign-from
  [mmsg {:keys [default-from]} {:keys [from] :as props}]
  (let [from (or from default-from)]
    (when from
      (let [from (parse-address from)]
        (.addFrom ^MimeMessage mmsg from)))))

(defn- assign-reply-to
  [mmsg {:keys [default-reply-to] :as cfg} {:keys [reply-to] :as params}]
  (let [reply-to (or reply-to default-reply-to)]
    (when reply-to
      (let [reply-to (parse-address reply-to)]
        (.setReplyTo ^MimeMessage mmsg reply-to)))))

(defn- assign-subject
  [mmsg {:keys [subject charset] :or {charset "utf-8"}}]
  (assert (string? subject) "subject is mandatory")
  (.setSubject ^MimeMessage mmsg
               ^String subject
               ^String charset))

(defn- assign-extra-headers
  [^MimeMessage mmsg {:keys [headers extra-data] :as params}]
  (let [headers (assoc headers "X-Penpot-Data" extra-data)]
    (reduce-kv (fn [^MimeMessage mmsg k v]
                 (doto mmsg
                   (.addHeader (name k) (str v))))
               mmsg
               headers)))

(defn- assign-body
  [^MimeMessage mmsg {:keys [body charset] :or {charset "utf-8"}}]
  (let [mpart (MimeMultipart. "mixed")]
    (cond
      (string? body)
      (let [bpart (MimeBodyPart.)]
        (.setContent bpart ^String body (str "text/plain; charset=" charset))
        (.addBodyPart mpart bpart))

      (vector? body)
      (let [mmp (MimeMultipart. "alternative")
            mbp (MimeBodyPart.)]
        (.addBodyPart mpart mbp)
        (.setContent mbp mmp)
        (doseq [item body]
          (let [mbp (MimeBodyPart.)]
            (.setContent mbp
                         ^String (:content item)
                         ^String (str (:type item "text/plain") "; charset=" charset))
            (.addBodyPart mmp mbp))))

      (map? body)
      (let [bpart (MimeBodyPart.)]
        (.setContent bpart
                     ^String (:content body)
                     ^String (str (:type body "text/plain") "; charset=" charset))
        (.addBodyPart mpart bpart))

      :else
      (throw (ex-info "Unsupported type" {:body body})))
    (.setContent mmsg mpart)
    mmsg))

(defn- build-message
  [cfg session params]
  (let [mmsg (MimeMessage. ^Session session)]
    (assign-recipients mmsg params)
    (assign-from mmsg cfg params)
    (assign-reply-to mmsg cfg params)
    (assign-subject mmsg params)
    (assign-extra-headers mmsg params)
    (assign-body mmsg params)
    (.saveChanges mmsg)
    mmsg))

(defn- opts->props
  [{:keys [username tls host port timeout default-from]
    :or {timeout 30000}
    :as opts}]
  (reduce-kv
   (fn [^Properties props k v]
     (if (nil? v)
       props
       (doto props (.put ^String k  ^String (str v)))))
   (Properties.)
   {"mail.user" username
    "mail.host" host
    "mail.from" default-from
    "mail.smtp.auth" (boolean username)
    "mail.smtp.starttls.enable" tls
    "mail.smtp.starttls.required" tls
    "mail.smtp.host" host
    "mail.smtp.port" port
    "mail.smtp.user" username
    "mail.smtp.timeout" timeout
    "mail.smtp.connectiontimeout" timeout}))

(defn smtp-session
  [{:keys [debug] :or {debug false} :as opts}]
  (let [props   (opts->props opts)
        session (Session/getInstance props)]
    (.setDebug session debug)
    session))

(defn ^MimeMessage smtp-message
  [cfg message]
  (let [^Session session (smtp-session cfg)]
    (build-message cfg session message)))

;; TODO: specs for smtp config

(defn send!
  [cfg message]
  (let [^MimeMessage message (smtp-message cfg message)]
    (Transport/send message
                    (:username cfg)
                    (:password cfg))
    nil))

;; ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Template Email Building
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private email-path "emails/%(id)s/%(lang)s.%(type)s")

(defn- render-email-template-part
  [type id context]
  (let [lang (:lang context :en)
        path (str/format email-path {:id (name id)
                                     :lang (name lang)
                                     :type (name type)})]
    (some-> (io/resource path)
            (tmpl/render context))))

(defn- build-email-template
  [id context]
  (let [subj (render-email-template-part :subj id context)
        text (render-email-template-part :txt id context)
        html (render-email-template-part :html id context)]
    (when (or (not subj)
              (and (not text)
                   (not html)))
      (ex/raise :type :internal
                :code :missing-email-templates))
    {:subject subj
     :body (d/concat
            [{:type "text/plain"
              :content text}]
            (when html
              [{:type "text/html"
                :content html}]))}))

(s/def ::priority #{:high :low})
(s/def ::to (s/or :single ::us/email
                  :multi (s/coll-of ::us/email)))
(s/def ::from ::us/email)
(s/def ::reply-to ::us/email)
(s/def ::lang string?)
(s/def ::extra-data ::us/string)

(s/def ::context
  (s/keys :req-un [::to]
          :opt-un [::reply-to ::from ::lang ::priority ::extra-data]))

(defn template-factory
  ([id] (template-factory id {}))
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
           email   (build-email-template id context)]
       (when-not email
         (ex/raise :type :internal
                   :code :email-template-does-not-exists
                   :hint "seems like the template is wrong or does not exists."
                   :context {:id id}))
       (cond-> (assoc email :id (name id))
         (:extra-data context)
         (assoc :extra-data (:extra-data context))

         (:from context)
         (assoc :from (:from context))

         (:reply-to context)
         (assoc :reply-to (:reply-to context))

         (:to context)
         (assoc :to (:to context)))))))
