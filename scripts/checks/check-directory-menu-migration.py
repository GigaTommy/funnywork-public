"""Verify permission-preserving menu repair in an isolated PostgreSQL schema."""
import argparse
import getpass
import pathlib
import subprocess
import uuid

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--psql', default='psql')
parser.add_argument('--host', default='127.0.0.1')
parser.add_argument('--port', type=int, required=True)
parser.add_argument('--database', default='postgres')
parser.add_argument('--user', default=getpass.getuser())
args = parser.parse_args()
schema = 'fw_menu_check_' + uuid.uuid4().hex
command = [args.psql, '-X', '-v', 'ON_ERROR_STOP=1', '--single-transaction', '-h', args.host,
           '-p', str(args.port), '-U', args.user, '-d', args.database]
migration = (pathlib.Path(__file__).resolve().parents[2] / 'scripts/setup/ruoyi-directory-menu-fix.sql').read_text()


def sql(content, success=True):
    result = subprocess.run(command, input=f'SET search_path TO {schema};\n' + content,
                            text=True, capture_output=True)
    if success and result.returncode:
        raise RuntimeError(result.stderr)
    if not success and not result.returncode:
        raise RuntimeError('Expected reserved-menu conflict to reject migration')


def require(condition):
    return "DO $$ BEGIN IF NOT (" + condition + ") THEN RAISE EXCEPTION 'Assertion failed'; END IF; END $$;\n"


try:
    sql(f'''CREATE SCHEMA {schema}; SET search_path TO {schema};
CREATE TABLE sys_menu(menu_id bigint primary key,menu_name text,parent_id bigint,order_num int,path text,
 component text,is_frame int,is_cache int,menu_type text,visible text,status text,perms text,
 icon text,create_dept bigint,create_by bigint,create_time timestamp);
CREATE TABLE sys_role_menu(role_id bigint,menu_id bigint,primary key(role_id,menu_id));
CREATE TABLE sys_tenant_package(package_id bigint,menu_ids text);
''')
    base = "TRUNCATE sys_menu,sys_role_menu,sys_tenant_package;\n"
    expected = require("(SELECT count(*) FROM sys_menu WHERE (menu_id=8120 AND perms='funnyworker:desktop:read') OR (menu_id=8121 AND perms='funnyworker:directory:write'))=2")
    sql(base + migration + migration + expected + require('(SELECT count(*) FROM sys_role_menu)=0'))
    desktop = "INSERT INTO sys_menu(menu_id,perms) VALUES(8120,'funnyworker:desktop:read'); INSERT INTO sys_role_menu VALUES(7,8120); INSERT INTO sys_tenant_package VALUES(1,'1,8120,99');\n"
    sql(base + desktop + migration + migration + expected
        + require('(SELECT count(*) FROM sys_role_menu WHERE role_id=7 AND menu_id=8120)=1')
        + require('(SELECT count(*) FROM sys_role_menu WHERE menu_id=8121)=0')
        + require("(SELECT menu_ids FROM sys_tenant_package WHERE package_id=1)='1,8120,99'"))
    directory = desktop.replace('funnyworker:desktop:read', 'funnyworker:directory:write')
    sql(base + directory + migration + migration + expected
        + require('(SELECT count(*) FROM sys_role_menu WHERE role_id=7 AND menu_id=8121)=1')
        + require('(SELECT count(*) FROM sys_role_menu WHERE menu_id=8120)=0')
        + require("(SELECT menu_ids FROM sys_tenant_package WHERE package_id=1)='1,8121,99'"))
    sql(base + desktop + "INSERT INTO sys_menu(menu_id,perms) VALUES(8121,'custom:permission');")
    sql(migration, success=False)
    sql(require("(SELECT perms FROM sys_menu WHERE menu_id=8121)='custom:permission'")
        + require('(SELECT count(*) FROM sys_role_menu WHERE role_id=7 AND menu_id=8120)=1'))
    sql(base + desktop + "INSERT INTO sys_menu(menu_id,perms) VALUES(8121,NULL);")
    sql(migration, success=False)
    sql(require('(SELECT count(*) FROM sys_menu WHERE menu_id=8121 AND perms IS NULL)=1'))
    print('Five PostgreSQL menu migration cases passed; role and package meanings preserved.')
finally:
    sql(f'DROP SCHEMA IF EXISTS {schema} CASCADE;')
