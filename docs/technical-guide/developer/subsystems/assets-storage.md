---
title: Assets storage
---

# Assets storage

The [storage.clj](https://github.com/penpot/penpot/blob/develop/backend/src/app/storage.clj)
is a module that manages storage of binary objects. It's a generic utility
that may be used for any kind of user uploaded files. Currently:

 * Image assets in Penpot files.
 * Uploaded fonts.
 * Profile photos of users and teams.

There is an abstract interface and several implementations (or **backends**),
depending on where the objects are actually stored:

 * `:assets-fs` stores ojects in the file system, under a given base path.
 * `:assets-s3` stores them in any cloud storage with an AWS-S3 compatible
   interface.
 * `:assets-db` stores them inside the PostgreSQL database, in a special table
   with a binary column.

## Storage API

The **StorageObject** record represents one stored object. It contains the
metadata, that is always stored in the database (table `storage_object`),
while the actual object data goes to the backend.

 * `:id` is the identifier you use to reference the object, may be stored
   in other places to represent the relationship with other element.
 * `:backend` points to the backend where the object data resides.
 * `:created-at` is the date/time of object creation.
 * `:deleted-at` is the date/time of object marked for deletion (see below).
 * `:expired-at` allows to create objects that are automatically deleted
   at some time (useful for temporary objects).
 * `:touched-at` is used to check objects that may need to be deleted (see
   below).

Also more metadata may be attached to objects, such as the `:content-type` or
the `:bucket` (see below).

You can use the API functions to manipulate objects. For example `put-object!`
to create a new one, `get-object` to retrieve the StorageObject,
`get-object-data` or `get-object-bytes` to read the binary contents, etc.

For profile photos or fonts, the object id is stored in the related table,
without further ado. But for file images, one more indirection is used. The
**file-media-object** is an abstraction that represents one image uploaded
by the user (in the future we may support other multimedia types). It has its
own database table, and references two `StorageObjects`, one for the original
file and another one for the thumbnail. Image shapes contains the id of the
`file-media-object` with the `:is-local` property as true. Image assets in the
file library also have a `file-media-object` with `:is-local` false,
representing that the object may be being used in other files.

## Serving objects

Stored objects are always served by Penpot (even if they have a public URL,
like when `:s3` storage are used). We have an endpoint `/assets` with three
variants:

```
/assets/by-id/<uuid>
/assets/by-file-media-id/<uuid>
/assets/by-file-media-id/<uuid>/thumbnail
```

They take an object and retrieve its data to the user. For `:db` backend, the
data is extracted from the database and served by the app. For the other ones,
we calculate the real url of the object, and pass it to our **nginx** server,
via special HTTP headers, for it to retrieve the data and serve it to the user.

This is the same in all environments (devenv, production or on premise).

## Object buckets

Obects may be organized in **buckets**, that are a kind of "intelligent" folders
(not related to AWS-S3 buckets, this is a Penpot internal concept).

The storage module may use the bucket (hardcoded) to make special treatment to
object, such as storing in a different path, or guessing how to know if an object
is referenced from other place.

## Sharing and deleting objects

To save storage space, duplicated objects wre shared. So, if for example
several users upload the same image, or a library asset is instantiated many
times, even by different users, the object data is actuall stored only once.

To achieve this, when an object is uploaded, its content is hashed, and the
hash compared with other objects in the same bucket. If there is a match,
the `StorabeObject` is reused. Thus, there may be different, unrelated, shapes
or library assets whose `:object-id` is the same.

### Garbage collector and reference count

Of course, when objects are shared, we cannot delete it directly when the
associated item is removed or unlinked. Instead, we need some mechanism to
track the references, and a garbage collector that deletes any object that
is no longer referenced.

We don't use explicit reference counts or indexes. Instead, the storage system
is intelligent enough to search, depending on the bucket (one for profile
photos, other for file media objects, etc.) if there is any element that is
using the object. For example, in the first case we look for user or team
profiles where the `:photo-id` field matches the object id.

When one item stops using one storage object (e. g. an image shape is deleted),
we mark the object as `:touched`. A periodic task revises all touched objectsm
checking if they are still referenced in other places. If not, they are marked
as :deleted. They're preserved in this state for some time (to allow "undeletion"
if the user undoes the change), and eventually, another garbage collection task
definitively deletes it, both in the backend and in the database table.

For `file-media-object`s, there is another collector, that periodically checks
if a media object is referenced by any shape or asset in its file. If not, it
marks the object as `:touched`, triggering the process described above.

