/*
 * Copyright (c) 2016-2018 VMware, Inc. All Rights Reserved.
 *
 * This product is licensed to you under the Apache License, Version 2.0 (the "License").
 * You may not use this product except in compliance with the License.
 *
 * This product may include a number of subcomponents with separate copyright notices
 * and license terms. Your use of these subcomponents is subject to the terms and
 * conditions of the subcomponent's license, as noted in the LICENSE file.
 */

import imageUtils from 'core/imageUtils';

describe('image utils test', function() {
  describe('getImageNamespaceAndName', function() {
    it('should parse namespace and name', function() {
      expect(imageUtils.getImageNamespaceAndName('http://private-registry.com/ns/category/imagename:tag')).toEqual('ns/category/imagename');
      expect(imageUtils.getImageNamespaceAndName('http://private-registry.com/ns/imagename:tag')).toEqual('ns/imagename');
      expect(imageUtils.getImageNamespaceAndName('http://private-registry.com/imagename:tag')).toEqual('imagename');
      expect(imageUtils.getImageNamespaceAndName('ns/imagename:tag')).toEqual('ns/imagename');
      expect(imageUtils.getImageNamespaceAndName('imagename:tag')).toEqual('imagename');
    });
  });
});