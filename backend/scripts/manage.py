#!/usr/bin/env python3
#
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.
#
# Copyright (c) KALEIDOS INC

import argparse
import json
import socket
import sys

from tabulate import tabulate
from getpass import getpass
from urllib.parse import urlparse

PREPL_URI = "tcp://localhost:6063"

def get_prepl_conninfo():
    uri_data = urlparse(PREPL_URI)
    if uri_data.scheme != "tcp":
        raise RuntimeError(f"invalid PREPL_URI: {PREPL_URI}")

    if not isinstance(uri_data.netloc, str):
        raise RuntimeError(f"invalid PREPL_URI: {PREPL_URI}")

    host, port = uri_data.netloc.split(":", 2)

    if port is None:
        port = 6063

    if isinstance(port, str):
        port = int(port)

    return host, port

def send(data):
    host, port = get_prepl_conninfo()
    with socket.create_connection((host, port)) as s:
        f = s.makefile(mode="rw")

        json.dump(data, f)
        f.write("\n")
        f.flush()

        while True:
            line = f.readline()
            result = json.loads(line)
            tag = result.get("tag", None)

            if tag == "ret":
                return result.get("val", None), result.get("err", None)
            elif tag == "out":
                print(result.get("val"), end="")
            else:
                raise RuntimeError("unexpected response from PREPL")

def print_error(error):
    print("ERR:", error["hint"])

def run_cmd(params):
    try:
        res, err = send(params)
        if err:
            print_error(err)
            sys.exit(-1)

        return res
    except Exception as cause:
        print("EXC:", str(cause))
        sys.exit(-2)

def create_profile(fullname, email, password, skip_tutorial=False, skip_walkthrough=False):
    props = {}
    if skip_tutorial:
        props["viewed-tutorial?"] = True
    if skip_walkthrough:
        props["viewed-walkthrough?"] = True

    params = {
        "cmd": "create-profile",
        "params": {
            "fullname": fullname,
            "email": email,
            "password": password,
            **props
        }
    }

    res = run_cmd(params)
    print(f"Created: {res['email']} / {res['id']}")


def update_profile(email, fullname, password, is_active):
    params = {
        "cmd": "update-profile",
        "params": {
            "email": email,
            "fullname": fullname,
            "password": password,
            "isActive": is_active
        }
    }

    res = run_cmd(params)
    if res is True:
        print(f"Updated")
    else:
        print(f"No profile found with email {email}")

def delete_profile(email, soft):
    params = {
        "cmd": "delete-profile",
        "params": {
            "email": email,
            "soft": soft
        }
    }

    res = run_cmd(params)
    if res is True:
        print(f"Deleted")
    else:
        print(f"No profile found with email {email}")

def search_profile(email):
    params = {
        "cmd": "search-profile",
        "params": {
            "email": email,
        }
    }

    res = run_cmd(params)

    if isinstance(res, list):
        print(tabulate(res, headers="keys"))

def derive_password(password):
    params = {
        "cmd": "derive-password",
        "params": {
            "password": password
        }
    }

    res = run_cmd(params)
    print(f"Derived password: \"{res}\"")


def migrate_components_v2():
    params = {
        "cmd": "migrate-v2",
        "params": {}
    }

    run_cmd(params)

available_commands = (
    "create-profile",
    "update-profile",
    "delete-profile",
    "search-profile",
    "derive-password",
)

parser = argparse.ArgumentParser(
    description=(
        "Penpot Command Line Interface (CLI)"
    )
)

parser.add_argument("-V", "--version", action="version", version="Penpot CLI %%develop%%")
parser.add_argument("action", action="store", choices=available_commands)
parser.add_argument("-f", "--force", help="force operation", action="store_true")
parser.add_argument("-n", "--fullname", help="fullname", action="store")
parser.add_argument("-e", "--email", help="email", action="store")
parser.add_argument("-p", "--password", help="password", action="store")
parser.add_argument("-c", "--connect", help="connect to PREPL", action="store", default="tcp://localhost:6063")
parser.add_argument("--skip-tutorial", help="mark tutorial as viewed", action="store_true")
parser.add_argument("--skip-walkthrough", help="mark walkthrough as viewed", action="store_true")

args = parser.parse_args()

PREPL_URI = args.connect

if args.action == "create-profile":
    email = args.email
    password = args.password
    fullname = args.fullname

    if email is None:
        email = input("Email: ")

    if fullname is None:
        fullname = input("Fullname: ")

    if password is None:
        password = getpass("Password: ")

    create_profile(fullname, email, password)

elif args.action == "update-profile":
    email = args.email
    password = args.password

    if email is None:
        email = input("Email: ")

    if password is None:
        password = getpass("Password: ")

    update_profile(email, None, password, None)

elif args.action == "derive-password":
    password = args.password

    if password is None:
        password = getpass("Password: ")

    derive_password(password)

elif args.action == "delete-profile":
    email = args.email
    soft = not args.force

    if email is None:
        email = input("Email: ")

    delete_profile(email, soft)

elif args.action == "search-profile":
    email = args.email
    if email is None:
        email = input("Email: ")

    search_profile(email)


