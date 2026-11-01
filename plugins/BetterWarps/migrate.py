import os
import re
import sqlite3
from datetime import datetime
from typing import Optional

DB_IN = '/home/mis/misplease/server/plugins/BetterWarps/better-warps.db'
DB_OUT = './better-warps.db'
SERVER_LOGS = '/home/mis/misplease/server/logs'
USERDATA_DIR = '/home/mis/misplease/server/plugins/Essentials/userdata'

Log = tuple[datetime, str]
Logs = list[Log]


def get_all_homes():
    con = sqlite3.connect(DB_IN)
    cur = con.cursor()
    res = cur.execute('SELECT * FROM homes')
    all_homes = res.fetchall()
    con.close()
    return all_homes


def get_all_warps():
    con = sqlite3.connect(DB_IN)
    cur = con.cursor()
    res = cur.execute('SELECT * FROM warps')
    all_homes = res.fetchall()
    con.close()
    return all_homes


def get_spawn():
    con = sqlite3.connect(DB_IN)
    cur = con.cursor()
    res = cur.execute('SELECT * FROM spawn')
    spawn = res.fetchone()
    con.close()
    return spawn


def create_new_db():
    con = sqlite3.connect(DB_OUT)
    cur = con.cursor()
    cur.execute("""
        CREATE TABLE IF NOT EXISTS homes (
            uuid TEXT NOT NULL,
            name TEXT NOT NULL,
            material TEXT NOT NULL,
            enchanted INTEGER NOT NULL,
            description TEXT NOT NULL,
            world TEXT NOT NULL,
            x REAL NOT NULL,
            y REAL NOT NULL,
            z REAL NOT NULL,
            yaw REAL NOT NULL,
            pitch REAL NOT NULL,
            uses INTEGER NOT NULL DEFAULT 0,
            last_used TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
            PRIMARY KEY(uuid, name)
        )
    """)
    cur.execute("""
        CREATE TABLE IF NOT EXISTS warps (
            name TEXT PRIMARY KEY NOT NULL,
            material TEXT NOT NULL,
            enchanted INTEGER NOT NULL,
            description TEXT NOT NULL,
            world TEXT NOT NULL,
            x REAL NOT NULL,
            y REAL NOT NULL,
            z REAL NOT NULL,
            yaw REAL NOT NULL,
            pitch REAL NOT NULL
        )
    """)
    cur.execute("""
        CREATE TABLE IF NOT EXISTS spawn (
            world TEXT NOT NULL,
            x REAL NOT NULL,
            y REAL NOT NULL,
            z REAL NOT NULL,
            yaw REAL NOT NULL,
            pitch REAL NOT NULL
        )
    """)
    cur.execute("""
        CREATE TABLE IF NOT EXISTS warp_usages (
            uuid TEXT NOT NULL,
            warp TEXT NOT NULL,
            uses INTEGER NOT NULL DEFAULT 0,
            last_used TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
            PRIMARY KEY(uuid, warp)
        )
    """)
    con.commit()
    con.close()


def get_proper_date_and_log(file: str, log: str) -> Optional[Log]:
    if log.strip() == '':
        return None

    match = re.match(r'\[(\d+:\d+:\d+)\] (.*)', log)
    if match is None:
        return None
    if file != 'latest.log':
        date = re.match(r'\d+-\d+-\d+', file)
        if date is None:
            raise RuntimeError(f"file '{file}' doesn't match the expected date pattern")
        new_date = datetime.strptime(f'{date.group(0)} {match.group(1)}', '%Y-%m-%d %H:%M:%S')
    else:
        latest_log_date = datetime.fromtimestamp(os.path.getmtime(file)).strftime('%Y-%m-%d')
        new_date = datetime.strptime(f'{latest_log_date} {match.group(1)}', '%Y-%m-%d %H:%M:%S')
    return new_date, match.group(2)


def get_each_line_with_date(file: str) -> Logs:
    logs: Logs = []
    with open(file, 'r', errors='ignore') as f:
        lines = f.read().split('\n')
    for line in lines:
        result = get_proper_date_and_log(file, line)
        if result is not None:
            date, log = result
            logs.append((date, log))
    return logs


def get_all_logs() -> Logs:
    pre = os.getcwd()
    os.chdir(SERVER_LOGS)

    logs_gz = [f for f in os.listdir() if f.endswith('.log.gz')]

    for log in logs_gz:
        os.system(f'yes n | gzip -dk {log}')

    logs = []

    for file in os.listdir():
        if file.endswith('.log'):
            logs.extend(get_each_line_with_date(file))

    logs.sort(key=lambda x: x[0])
    os.chdir(pre)
    return logs


def get_username_from_yaml(yaml: str, filename: str) -> Optional[str]:
    name = re.search(r'last-account-name: (\S+)', yaml)
    if name is not None:
        return name.group(1)
    else:
        name = re.search(r'lastAccountName: (\S+)', yaml)
        if name is not None:
            return name.group(1)
        else:
            print(f"no name found on '{filename}'")
            return None


def get_all_names_and_uuids():
    uuids = dict()
    pre = os.getcwd()
    os.chdir(USERDATA_DIR)
    files = [f for f in os.listdir() if f.endswith('.yml')]
    for file in files:
        with open(file, 'r') as f:
            yaml = f.read()
            name = get_username_from_yaml(yaml, file)
            if name is not None:
                uuids[file.split('.')[0]] = name
    os.chdir(pre)
    return uuids


def get_all_home_usages(logs: Logs):
    home_search = re.compile(r'^\[Server thread/INFO\]: (\w+) issued server command: /home "?(.*?)"?$')
    usages = []
    for date, log in logs:
        match = home_search.match(log)
        if match:
            usages.append({'date': date, 'home': match.group(2), 'username': match.group(1)})
    return usages


def get_all_warp_usages(logs: Logs):
    warp_search = re.compile(r'^\[Server thread/INFO\]: (\w+) issued server command: /warp "?(.*?)"?$')
    usages = []
    for date, log in logs:
        match = warp_search.match(log)
        if match:
            usages.append({'date': date, 'warp': match.group(2), 'username': match.group(1)})
    return usages


def get_user_home_usages(username: str, home_name: str, usages) -> list[datetime]:
    return [usage['date'] for usage in usages if usage['home'] == home_name and usage['username'] == username]


def get_user_warp_usages(username: str, warp_name: str, usages) -> list[datetime]:
    return [usage['date'] for usage in usages if usage['warp'] == warp_name and usage['username'] == username]


def insert_old_homes(old_homes, names: dict[str, str], home_usages):
    con = sqlite3.connect(DB_OUT)
    cur = con.cursor()
    for home in old_homes:
        usages = get_user_home_usages(names[home[0]], home[1], home_usages)
        cur.execute('INSERT INTO homes(uuid, name, material, enchanted, description, world, x, y, z, yaw, pitch, uses, last_used) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)',
                    home + (len(usages), sorted(usages)[-1] if len(usages) else datetime.min))
    con.commit()
    con.close()


def insert_old_warps(old_warps):
    con = sqlite3.connect(DB_OUT)
    cur = con.cursor()
    for warp in old_warps:
        cur.execute(
            'INSERT INTO warps(name, material, enchanted, description, world, x, y, z, yaw, pitch) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)', warp)
    con.commit()
    con.close()


def insert_old_spawn(spawn):
    con = sqlite3.connect(DB_OUT)
    cur = con.cursor()
    cur.execute('INSERT INTO spawn(world, x, y, z, yaw, pitch) VALUES(?, ?, ?, ?, ?, ?)', spawn)
    con.commit()
    con.close()


def insert_warp_usages(warps, names: dict[str, str], warp_usages):
    con = sqlite3.connect(DB_OUT)
    cur = con.cursor()

    for uuid, name in names.items():
        for warp in warps:
            if name == 'misdocumeno':
                print(f'checking usages of warp "{warp[0]}" for player "{name}"')
            usages = get_user_warp_usages(name, warp[0], warp_usages)
            if len(usages):
                print(f'{warp[0]} was used {len(usages)} times')
                cur.execute('INSERT INTO warp_usages(uuid, warp, uses, last_used) VALUES(?, ?, ?, ?)',
                            (uuid, warp[0], len(usages), sorted(usages)[-1]))

    con.commit()
    con.close()


def main():
    print('getting logs...')
    logs = get_all_logs()
    print('got all logs')

    print('getting home usages...')
    home_usages = get_all_home_usages(logs)
    print('got home usages')
    print('getting warp usages...')
    warp_usages = get_all_warp_usages(logs)
    print('got warp usages')
    print('getting usernames...')
    usernames = get_all_names_and_uuids()
    print('got usernames')

    print('getting homes, warps and spawn from old db...')
    homes = get_all_homes()
    warps = get_all_warps()
    spawn = get_spawn()
    print('got homes, warps and spawn from old db')

    print('creating new db...')
    create_new_db()

    print('inserting old homes, warps and spawn...')
    insert_old_homes(homes, usernames, home_usages)
    insert_old_warps(warps)
    insert_old_spawn(spawn)
    insert_warp_usages(warps, usernames, warp_usages)

    print('done')


if __name__ == '__main__':
    main()
