#!/usr/bin/env python

from setuptools import setup, find_packages
import configparser
import itertools
import os

def read_version_name():
  config = configparser.ConfigParser()
  path = os.path.dirname(os.path.realpath(__file__))
  filename = '%s/../../gradle.properties' % (path)
  with open(filename) as fp:
    config.read_file(itertools.chain(['[properties]'], fp), source=filename)
  return config['properties']['VERSION_NAME']

version = read_version_name()
print('Version is %s' % (version))

setup(
  name = 'stetho',
  packages = [ 'stetho' ],
  version = version,
  description = 'Scripting interface for the Stetho Android debugging bridge',
  author = 'Josh Guilfoyle',
  author_email = 'jasta@devtcg.org',
  url = 'https://github.com/facebook/stetho',
  keywords = [ 'debug', 'dumpapp', 'android' ],
  classifiers = [
    'Development Status :: 5 - Production/Stable',

    'Intended Audience :: Developers',
    'Topic :: Software Development :: Debuggers',
    'Topic :: Software Development :: Testing',
  ],
  entry_points = {
    'console_scripts': [
      'dumpapp=stetho:dumpapp_main',
    ],
  }
)
