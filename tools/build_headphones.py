"""Turn the Sketchfab WH-1000XM4 model into the light asset used by the headphones card.

    blender -b --factory-startup --python tools/build_headphones.py -- SOURCE.glb \
        app/src/main/assets/headphones.glb [--preview DIR HDR]

Source (not in the repo, 30 MB): "Sony WH-1000XM4 - Black and brown" by La_Grekula,
https://sketchfab.com/3d-models/sony-wh-1000xm4-black-and-brown-18e9aa472d884f95897a3e70ee38026b,
CC BY 4.0. Changes: brand logos removed, meshes decimated, textures shrunk to JPEG (512 px,
1024 px for the leather), leather darkened to black with a stronger grain, centred and scaled to the buds scene (cm, about 7 cm tall).
The app turns it like a turntable (BudsScene.kt); nothing is animated here.
"""
import sys

import bpy
import numpy as np
from mathutils import Matrix, Vector

HEIGHT = 7.0       # cm, so it fills the card like the buds scene does
CENTRE_Z = 1.3     # the camera's aim point, so the app's turntable keeps it framed
MAX_FACES = 40_000
TEXTURE = 512
LEATHER_TEXTURE = 1024  # ear pads and headband: the grain is the point, so they keep more detail
LEATHER_RELIEF = 1.6


def main():
    args = sys.argv[sys.argv.index('--') + 1:]
    bpy.ops.wm.read_factory_settings(use_empty=True)
    bpy.ops.import_scene.gltf(filepath=args[0])
    objs = bpy.data.objects
    for o in [o for o in objs if o.name.startswith('logo')]:  # brand name on the headband
        bpy.data.objects.remove(o)
    meshes = [o for o in objs if o.type == 'MESH']

    # Bake every transform into the meshes, then drop the empties.
    for o in objs:
        o.select_set(o in meshes)
    bpy.context.view_layer.objects.active = meshes[0]
    bpy.ops.object.parent_clear(type='CLEAR_KEEP_TRANSFORM')
    bpy.ops.object.transform_apply(location=True, rotation=True, scale=True)
    for o in [o for o in objs if o.type != 'MESH']:
        bpy.data.objects.remove(o)

    total = sum(len(o.data.polygons) for o in meshes)
    for o in meshes:  # only the heavy parts lose faces; small ones keep their shape
        share = len(o.data.polygons) / total
        if share > 0.05:
            mod = o.modifiers.new('decimate', 'DECIMATE')
            mod.ratio = max(0.05, MAX_FACES / total)
            bpy.context.view_layer.objects.active = o
            bpy.ops.object.modifier_apply(modifier=mod.name)

    leather = bpy.data.materials.get('Leather')
    keep = {n.image for n in leather.node_tree.nodes if n.bl_idname == 'ShaderNodeTexImage'} if leather else set()
    for img in bpy.data.images:
        size = LEATHER_TEXTURE if img in keep else TEXTURE
        if img.size[0] > size:
            img.scale(size, size)

    if leather:  # the source's brown; the WH-1000XM4 is all black, with a visible leather grain
        bsdf = leather.node_tree.nodes.get('Principled BSDF')
        mix = leather.node_tree.nodes.new('ShaderNodeMix')
        mix.data_type = 'RGBA'
        mix.blend_type = 'MULTIPLY'
        mix.inputs['Factor'].default_value = 1.0
        mix.inputs['B'].default_value = (0.3, 0.3, 0.3, 1.0)
        src = bsdf.inputs['Base Color'].links[0].from_socket
        leather.node_tree.links.new(src, mix.inputs['A'])
        leather.node_tree.links.new(mix.outputs['Result'], bsdf.inputs['Base Color'])
        for n in leather.node_tree.nodes:
            if n.bl_idname == 'ShaderNodeNormalMap':
                n.inputs['Strength'].default_value = LEATHER_RELIEF
        # Grey the brown colour map itself: the exporter keeps texture × factor, not a desaturate node.
        maps = {l.from_node.image for n in leather.node_tree.nodes if n.bl_idname in ('ShaderNodeNormalMap', 'ShaderNodeSeparateColor')
                for l in n.inputs[0 if n.bl_idname == 'ShaderNodeSeparateColor' else 'Color'].links}
        for img in keep - maps:
            px = np.array(img.pixels[:]).reshape(-1, 4)
            px[:, :3] = (px[:, :3] @ np.array([0.2126, 0.7152, 0.0722]))[:, None]
            img.pixels[:] = px.ravel()

    # Ear cups side by side along X, centred, front towards -Y like the buds scene.
    for o in meshes:
        o.select_set(True)
    bpy.ops.object.join()
    hp = bpy.context.view_layer.objects.active
    hp.name = hp.data.name = 'Headphones'
    hp.data.transform(Matrix.Rotation(-1.5708, 4, 'Z'))
    lo = Vector([min(v.co[i] for v in hp.data.vertices) for i in range(3)])
    hi = Vector([max(v.co[i] for v in hp.data.vertices) for i in range(3)])
    hp.data.transform(Matrix.Translation((0, 0, CENTRE_Z)) @ Matrix.Scale(HEIGHT / (hi.z - lo.z), 4) @ Matrix.Translation(-(hi + lo) / 2))
    print('FACES', len(hp.data.polygons), 'SIZE', tuple(round((hi - lo)[i] * HEIGHT / (hi.z - lo.z), 2) for i in range(3)))

    bpy.ops.export_scene.gltf(
        filepath=args[1], export_format='GLB', export_animations=False, export_cameras=False,
        export_lights=False, export_extras=False, export_yup=True, export_image_format='JPEG')

    if '--preview' in args:
        i = args.index('--preview')
        preview(args[i + 1], args[i + 2])


def preview(out, hdr):
    sc = bpy.context.scene
    sc.render.engine = 'BLENDER_EEVEE'
    sc.render.film_transparent = True
    sc.render.resolution_x, sc.render.resolution_y = 640, 400
    w = bpy.data.worlds.new('w')
    sc.world = w
    w.use_nodes = True
    env = w.node_tree.nodes.new('ShaderNodeTexEnvironment')
    env.image = bpy.data.images.load(hdr)
    w.node_tree.links.new(env.outputs[0], w.node_tree.nodes['Background'].inputs[0])
    cam = bpy.data.objects.new('cam', bpy.data.cameras.new('cam'))
    sc.collection.objects.link(cam)
    sc.camera = cam
    cam.data.sensor_fit = 'VERTICAL'
    cam.data.sensor_height = 24.0
    cam.data.lens = 50.0
    eye, target = Vector((0.0, -21.0, 7.5)), Vector((0.0, 0.0, 1.3))  # same camera as the buds
    cam.location = eye
    cam.rotation_euler = (target - eye).to_track_quat('-Z', 'Y').to_euler()
    hp = bpy.data.objects['Headphones']
    hp.rotation_mode = 'XYZ'
    for deg in (0, 45, 90, 180):
        hp.rotation_euler = (0, 0, deg * 3.14159 / 180)
        sc.render.filepath = f'{out}/hp{deg:03d}.png'
        bpy.ops.render.render(write_still=True)
    hp.rotation_euler = (0, 0, 0.6)  # close-up on an ear pad, to judge the leather
    cam.data.lens = 150.0
    sc.render.filepath = f'{out}/pad.png'
    bpy.ops.render.render(write_still=True)


main()
