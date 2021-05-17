# Copyright (c) 2020, 2021 Oracle and/or its affiliates. All rights reserved.
# This file is made available under version 3 of the GNU General Public License.

import argparse
import json
import os
import shutil
import sys
import subprocess

from jupyter_client.kernelspec import KernelSpecManager

ALIASES = {
    "IJAVA_TIMEOUT": {
        "NO_TIMEOUT": "-1",
    },
}

NAME_MAP = {
    "timeout": "IJAVA_TIMEOUT",
}

def type_assertion(name, type_fn):
    env = NAME_MAP[name]
    aliases = ALIASES.get(env, {})

    def checker(value):
        alias = aliases.get(value, value)
        type_fn(alias)
        return alias
    setattr(checker, '__name__', getattr(type_fn, '__name__', 'type_fn'))
    return checker

class EnvVar(argparse.Action):
    def __init__(self, option_strings, dest, aliases=None, name_map=None, list_sep=None, **kwargs):
        super(EnvVar, self).__init__(option_strings, dest, **kwargs)

        if aliases is None: aliases = {}
        if name_map is None: name_map = {}

        self.aliases = aliases
        self.name_map = name_map
        self.list_sep = list_sep

        for name in self.option_strings:
            if name.lstrip('-') not in name_map:
                raise ValueError('Name "%s" is not mapped to an environment variable' % name.lstrip('-'))


    def __call__(self, parser, namespace, value, option_string=None):
        if option_string is None:
            raise ValueError('option_string is required')

        env = getattr(namespace, self.dest, None)
        if env is None:
            env = {}

        name = option_string.lstrip('-')
        env_var = self.name_map[name]

        if self.list_sep:
            old = env.get(env_var)
            value = old + self.list_sep + str(value) if old is not None else str(value)

        env[env_var] = value

        setattr(namespace, self.dest, env)

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description='Install the GraalVM kernel.')

    install_location = parser.add_mutually_exclusive_group()
    install_location.add_argument(
        '--user',
        help='Install to the per-user kernel registry.',
        action='store_true'
    )
    install_location.add_argument(
        '--sys-prefix',
        help="Install to Python's sys.prefix. Useful in conda/virtual environments.",
        action='store_true'
    )
    install_location.add_argument(
        '--prefix',
        help='''
        Specify a prefix to install to, e.g. an env.
        The kernelspec will be installed in PREFIX/share/jupyter/kernels/
        ''',
        default=''
    )

    parser.add_argument(
        "--timeout",
        dest="env",
        action=EnvVar,
        aliases=ALIASES,
        name_map=NAME_MAP,
        help="A duration specifying a timeout (in milliseconds by default) for a _single top level statement_. If less than `1` then there is no timeout. If desired a time may be specified with a `TimeUnit` may be given following the duration number (ex `\"30 SECONDS\"`).",
        type=type_assertion("timeout", str),
    )

    parser.add_argument(
        "--graalvm",
        help="Path to the GraalVM",
        default=None
    )

    parser.add_argument(
        "--native",
        help="Build and install native GraalVM with the provided set of comma-separated languages",
        default=None
    )

    args = parser.parse_args()

    if not hasattr(args, "env") or getattr(args, "env") is None:
        setattr(args, "env", {})

    # User installation by default
    if not args.sys_prefix and not args.prefix:
        args.user = True
    
    graalvm_home = args.graalvm
    if not graalvm_home and "GRAALVM_HOME" in os.environ:
        graalvm_home = os.environ["GRAALVM_HOME"]
    if not graalvm_home:
        print("Set GraalVM installation location via --graalvm option, or set GRAALVM_HOME environment variable")
        exit()

    # Run MVN package
    mvn_env = os.environ
    mvn_env["JAVA_HOME"] = graalvm_home
    ret = subprocess.run(['mvn', 'package'], env=mvn_env)
    if ret.returncode != 0:
        exit(ret.returncode)

    current_dir = os.path.dirname(os.path.abspath(__file__))
    kernel_dir = os.path.join(current_dir, 'graalvm')
    if not os.path.exists(kernel_dir):
        os.mkdir(kernel_dir)
    shutil.move(os.path.join(current_dir, "target/IGraalVM-1.0-SNAPSHOT.jar"), os.path.join(kernel_dir, "IGraalVM.jar"))

    kernel_name='GraalVM'
    if args.native:
        # Generate the native image
        languages = args.native.split(",")
        print("Generating native image with following languages: " + str(languages))
        native_run_args = [os.path.join(graalvm_home, 'bin/native-image'), '-H:+ReportExceptionStackTraces', '--no-fallback', '--report-unsupported-elements-at-runtime']
        for language in languages:
            native_run_args.append("--language:" + language)
        native_run_args.append('-jar')
        native_run_args.append('IGraalVM.jar')
        os.chdir('graalvm')
        ret = subprocess.run(native_run_args)
        if ret.returncode != 0:
            exit(ret.returncode)
        os.remove('IGraalVM.jar')
        os.chdir('..')
        kernel_name='GraalVMNative'

    # Install the kernel
    install_dest = KernelSpecManager().install_kernel_spec(
        kernel_dir,
        kernel_name=kernel_name,
        user=args.user,
        prefix=sys.prefix if args.sys_prefix else args.prefix,
    )

    # Connect the self referencing token left in the kernel.json to point to it's install location.

    # Prepare the token replacement string which should be properly escaped for use in a JSON string
    # The [1:-1] trims the first and last " json.dumps adds for strings.
    install_dest_json_fragment = json.dumps(install_dest)[1:-1]

    # Prepare the paths to the installed kernel.json and the one bundled with this installer.
    local_kernel_json_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'graalvm', 'kernel.json')
    installed_kernel_json_path = os.path.join(install_dest, 'kernel.json')

    kernel_args = []
    if args.native:
        kernel_args = [
            install_dest + "/IGraalVM",
            "{connection_file}"
        ]
    else:
        kernel_args = [
            graalvm_home + "/bin/java",
            "-jar",
            install_dest + "/IGraalVM.jar",
            "{connection_file}"
        ]
    kernel_json = {
        "argv": kernel_args,
        "display_name": "GraalVM Native" if args.native else "GraalVM",
        "language": "GraalVM",
        "interrupt_mode": "message",
        "env": {
            "GRAALVM_HOME": graalvm_home
        }
    }

    kernel_env = kernel_json["env"]
    for k, v in args.env.items():
        kernel_env[k] = v
    with open(installed_kernel_json_path, 'w') as installed_kernel_json_file:
        json.dump(kernel_json, installed_kernel_json_file, indent=4, sort_keys=True)

    print('Installed GraalVM kernel into "%s"' % install_dest)
