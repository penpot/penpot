;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; This Source Code Form is "Incompatible With Secondary Licenses", as
;; defined by the Mozilla Public License, v. 2.0.
;;
;; Copyright (c) 2020 UXBOX Labs SL

(ns app.util.emails
  (:require
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

(defn build-address
  [v charset]
  (try
    (cond
      (string? v)
      (InternetAddress. v nil charset)

      (map? v)
      (InternetAddress. (:addr v)
                        (:name v)
                        (:charset v charset))

      :else
      (throw (ex-info "Invalid address" {:data v})))
    (catch Exception e
      (throw (ex-info "Invalid address" {:data v} e)))))

(defn- resolve-recipient-type
  [type]
  (case type
    :to  Message$RecipientType/TO
    :cc  Message$RecipientType/CC
    :bcc Message$RecipientType/BCC))

(defn- assign-recipient
  [^MimeMessage mmsg type address charset]
  (if (sequential? address)
    (reduce #(assign-recipient %1 type %2 charset) mmsg address)
    (let [address (build-address address charset)
          type    (resolve-recipient-type type)]
      (.addRecipient mmsg type address)
      mmsg)))

(defn- assign-recipients
  [mmsg {:keys [to cc bcc charset] :or {charset "utf-8"} :as params}]
  (cond-> mmsg
    (some? to)  (assign-recipient :to to charset)
    (some? cc)  (assign-recipient :cc cc charset)
    (some? bcc) (assign-recipient :bcc bcc charset)))

(defn- assign-from
  [mmsg {:keys [from charset] :or {charset "utf-8"}}]
  (when from
    (let [from (build-address from charset)]
      (.setFrom ^MimeMessage mmsg ^InternetAddress from))))

(defn- assign-reply-to
  [mmsg {:keys [defaut-reply-to]} {:keys [reply-to charset] :or {charset "utf-8"}}]
  (let [reply-to (or reply-to defaut-reply-to)]
    (when reply-to
      (let [reply-to (build-address reply-to charset)
            reply-to (into-array InternetAddress [reply-to])]
        (.setReplyTo ^MimeMessage mmsg reply-to)))))

(defn- assign-subject
  [mmsg {:keys [subject charset] :or {charset "utf-8"}}]
  (assert (string? subject) "subject is mandatory")
  (.setSubject ^MimeMessage mmsg
               ^String subject
               ^String charset))

(defn- assign-extra-headers
  [^MimeMessage mmsg {:keys [headers custom-data] :as params}]
  (let [headers (assoc headers "X-Sereno-Custom-Data" custom-data)]
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
    (assign-from mmsg params)
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
    "mail.smtp.auth" (boolean username)
    "mail.smtp.starttls.enable" tls
    "mail.smtp.starttls.required" tls
    "mail.smtp.host" host
    "mail.smtp.port" port
    "mail.smtp.from" default-from
    "mail.smtp.user" username
    "mail.smtp.timeout" timeout
    "mail.smtp.connectiontimeout" timeout}))

(defn smtp-session
  [{:keys [debug] :or {debug false} :as opts}]
  (let [props   (opts->props opts)
        session (Session/getInstance props)]
    (.setDebug session debug)
    session))

(defn smtp-message
  [cfg message]
  (let [^Session session (smtp-session cfg)]
    (build-message cfg session message)))

;; TODO: specs for smtp config

(defn send!
  [cfg message]
  (let [^MimeMessage message (smtp-message cfg message)]
    (Transport/send message (:username cfg) (:password cfg))
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
              (not text)
              (not html))
      (ex/raise :type :internal
                :code :missing-email-templates))
    {:subject subj
     :body [{:type "text/plain"
             :content text}
            {:type "text/html"
             :content html}]}))

(s/def ::priority #{:high :low})
(s/def ::to (s/or :sigle ::us/email
                  :multi (s/coll-of ::us/email)))
(s/def ::from ::us/email)
(s/def ::reply-to ::us/email)
(s/def ::lang string?)
(s/def ::custom-data ::us/string)

(s/def ::context
  (s/keys :req-un [::to]
          :opt-un [::reply-to ::from ::lang ::priority ::custom-data]))

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
         (:custom-data context)
         (assoc :custom-data (:custom-data context))

         (:from context)
         (assoc :from (:from context))

         (:reply-to context)
         (assoc :reply-to (:reply-to context))

         (:to context)
         (assoc :to (:to context)))))))
